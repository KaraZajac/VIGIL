package org.soulstone.vigil.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.vigil.data.db.SchemaJson.Schema
import java.io.File
import java.lang.reflect.Proxy

/**
 * Keeps the promise in [VigilDatabase.get]: from schema [VigilDatabase.FIRST_MIGRATABLE_VERSION]
 * on, an app update keeps the user's alert history and sightings.
 *
 * Without this, raising the version and forgetting the migration still builds, still
 * runs, and silently wipes the database on update, because the builder falls back to
 * a destructive migration. A migration that adds the wrong column is worse: Room
 * refuses the database on first open and the app cannot start.
 *
 * What it checks, with no database: every step has a migration, and each column the
 * exported schema gained is added by that migration with the same type, nullability
 * and default. It understands ADD COLUMN only. Any other schema change fails here
 * with a message saying so, and has to be covered by extending this test.
 *
 * What it does not check: that SQLite accepts the statements. That needs a device;
 * the 4 to 5 step was run on emulators (API 34 and 36) over a real version-4 database.
 */
class MigrationCoverageTest {

    private val dir = File("schemas/${VigilDatabase::class.java.name}")

    private fun schema(v: Int): Schema {
        val f = File(dir, "$v.json")
        assertTrue("no exported schema for version $v at ${f.path}", f.isFile)
        return SchemaJson.read(f).also { assertEquals("${f.name} says another version", v, it.version) }
    }

    /** The statements a migration runs, without running them. */
    private fun statements(m: Migration): List<String> {
        val sql = ArrayList<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args?.size == 1) {
                sql.add(args[0] as String)
                null
            } else {
                throw UnsupportedOperationException(
                    "migration called ${method.name}: MigrationCoverageTest only follows execSQL(String), extend it"
                )
            }
        } as SupportSQLiteDatabase
        m.migrate(db)
        return sql
    }

    @Test fun theNewestSchemaFileIsTheVersionTheAppDeclares() {
        schema(VigilDatabase.VERSION)
        val newest = dir.listFiles()!!.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.max()
        assertEquals("a schema file newer than VigilDatabase.VERSION exists", VigilDatabase.VERSION, newest)
    }

    @Test fun everyStepHasExactlyOneMigration() {
        val steps = VigilDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        val wanted = (VigilDatabase.FIRST_MIGRATABLE_VERSION until VigilDatabase.VERSION).map { it to it + 1 }
        assertEquals("MIGRATIONS must be one step per version, none missing, none extra", wanted, steps.sorted2())
    }

    @Test fun everyMigrationAddsWhatTheSchemaGained() {
        for (m in VigilDatabase.MIGRATIONS) {
            val problems = problems(schema(m.startVersion), schema(m.endVersion), statements(m))
            assertEquals("${m.startVersion} to ${m.endVersion}", emptyList<String>(), problems)
        }
    }

    // --- the checker itself is checked: it must notice each way of getting it wrong ---

    private val v4 by lazy { schema(4) }
    private val v5 by lazy { schema(5) }
    private val good = listOf(
        "ALTER TABLE trackers ADD COLUMN name TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE trackers ADD COLUMN vendor TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE trackers ADD COLUMN addrKind INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE sightings ADD COLUMN accuracy REAL"
    )

    private fun assertOneProblem(containing: String, sql: List<String>) {
        val p = problems(v4, v5, sql)
        assertEquals(p.toString(), 1, p.size)
        assertTrue(p.toString(), p[0].contains(containing))
    }

    @Test fun theKnownGoodStatementsPass() {
        assertEquals(emptyList<String>(), problems(v4, v5, good))
        // Quoting and case as Room itself writes them are accepted too.
        assertEquals(
            emptyList<String>(),
            problems(v4, v5, good.dropLast(1) + "alter table `sightings` add column `accuracy` real")
        )
    }

    @Test fun aForgottenColumnIsNoticed() =
        assertOneProblem("sightings.accuracy is in the new schema but no statement adds it", good.dropLast(1))

    @Test fun aWrongTypeIsNoticed() =
        assertOneProblem("type", good.dropLast(1) + "ALTER TABLE sightings ADD COLUMN accuracy INTEGER")

    @Test fun wrongNullabilityIsNoticed() =
        assertOneProblem("NOT NULL", good.dropLast(1) + "ALTER TABLE sightings ADD COLUMN accuracy REAL NOT NULL DEFAULT 0")

    @Test fun aWrongDefaultIsNoticed() =
        assertOneProblem("default", good.take(2) + "ALTER TABLE trackers ADD COLUMN addrKind INTEGER NOT NULL DEFAULT 1" + good[3])

    @Test fun aNotNullColumnWithoutADefaultIsNoticed() =
        assertOneProblem("default", good.take(2) + "ALTER TABLE trackers ADD COLUMN addrKind INTEGER NOT NULL" + good[3])

    @Test fun aColumnTheSchemaDoesNotHaveIsNoticed() =
        assertOneProblem("not in the new schema", good + "ALTER TABLE trackers ADD COLUMN extra TEXT")

    @Test fun anythingButAddColumnIsRefused() =
        assertOneProblem("extend", good + "CREATE INDEX i ON trackers(name)")

    @Test fun aChangedOrDroppedColumnIsRefused() {
        // Run backwards, 5 to 4 loses four columns: not something ADD COLUMN can do.
        val p = problems(v5, v4, emptyList())
        assertEquals(p.toString(), 4, p.size)
        assertTrue(p.toString(), p.all { it.contains("extend") })
    }

    private fun List<Pair<Int, Int>>.sorted2() = sortedWith(compareBy({ it.first }, { it.second }))

    companion object {
        private val ADD_COLUMN = Regex(
            """^\s*ALTER\s+TABLE\s+`?(\w+)`?\s+ADD\s+COLUMN\s+`?(\w+)`?\s+(\w+)(\s+NOT\s+NULL)?(?:\s+DEFAULT\s+(.+?))?\s*;?\s*$""",
            RegexOption.IGNORE_CASE
        )

        /** Everything wrong with [sql] as the way from [old] to [new]; empty when it fits. */
        internal fun problems(old: Schema, new: Schema, sql: List<String>): List<String> {
            val out = ArrayList<String>()
            val how = "this test only understands ADD COLUMN, extend it"

            if (old.tables.keys != new.tables.keys) {
                out.add("tables changed from ${old.tables.keys} to ${new.tables.keys}: $how")
                return out
            }

            // table.column -> what the statement says about it
            data class Added(val type: String, val notNull: Boolean, val default: String?)
            val added = LinkedHashMap<String, Added>()
            for (statement in sql) {
                val m = ADD_COLUMN.matchEntire(statement)
                if (m == null) {
                    out.add("\"$statement\": $how")
                    continue
                }
                val (table, column, type, notNull, default) = m.destructured
                val key = "$table.$column"
                if (added.put(key, Added(type.uppercase(), notNull.isNotEmpty(), default.ifEmpty { null })) != null) {
                    out.add("$key is added twice")
                }
            }

            for ((name, table) in new.tables) {
                val before = old.tables.getValue(name)
                if (before.shape != table.shape) out.add("$name: keys or indices changed: $how")
                val had = before.columns.associateBy { it.name }
                for (gone in had.keys - table.columns.map { it.name }.toSet()) {
                    out.add("$name.$gone was removed: $how")
                }
                for (c in table.columns) {
                    val key = "$name.${c.name}"
                    val was = had[c.name]
                    if (was != null) {
                        if (was != c) out.add("$key changed from $was to $c: $how")
                        continue
                    }
                    val a = added.remove(key)
                    if (a == null) {
                        out.add("$key is in the new schema but no statement adds it")
                        continue
                    }
                    if (a.type != c.affinity) out.add("$key: type ${a.type}, schema says ${c.affinity}")
                    if (a.notNull != c.notNull) {
                        out.add("$key: NOT NULL is ${a.notNull}, schema says ${c.notNull}")
                    } else if (c.notNull && a.default == null) {
                        // SQLite refuses to add a NOT NULL column without a default.
                        out.add("$key: NOT NULL needs a default")
                    } else if (c.default != null && a.default != c.default) {
                        // Room compares the default only when the entity declares one.
                        out.add("$key: default ${a.default}, schema says ${c.default}")
                    }
                }
            }
            for (key in added.keys) out.add("$key is added but is not in the new schema")
            return out
        }
    }
}
