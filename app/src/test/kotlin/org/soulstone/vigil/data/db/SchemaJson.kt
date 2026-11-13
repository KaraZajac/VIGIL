package org.soulstone.vigil.data.db

import java.io.File

/**
 * Just enough of Room's exported schema files (app/schemas) for the migration test
 * to compare two versions. Hand-rolled because unit tests here have no JSON library:
 * org.json exists only as an Android stub that throws on the JVM.
 */
internal object SchemaJson {

    data class Column(val name: String, val affinity: String, val notNull: Boolean, val default: String?)

    data class Table(
        val name: String,
        val columns: List<Column>,
        /** Primary key, indices and foreign keys, in a form that only needs comparing. */
        val shape: String
    )

    data class Schema(val version: Int, val tables: Map<String, Table>)

    fun read(file: File): Schema {
        val root = Parser(file.readText()).value() as Map<*, *>
        val db = root["database"] as Map<*, *>
        val tables = (db["entities"] as List<*>).map { e ->
            e as Map<*, *>
            val cols = (e["fields"] as List<*>).map { f ->
                f as Map<*, *>
                Column(
                    name = f["columnName"] as String,
                    affinity = f["affinity"] as String,
                    notNull = f["notNull"] as Boolean,
                    default = f["defaultValue"] as String?
                )
            }
            Table(
                name = e["tableName"] as String,
                columns = cols,
                shape = "pk=${e["primaryKey"]} indices=${e["indices"]} fk=${e["foreignKeys"]}"
            )
        }
        require((db["views"] as List<*>?).isNullOrEmpty()) { "views are not handled: extend SchemaJson" }
        return Schema((db["version"] as Number).toInt(), tables.associateBy { it.name })
    }

    /** A plain recursive-descent JSON reader: maps, lists, strings, numbers, booleans, null. */
    private class Parser(private val s: String) {
        private var i = 0

        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun lit(word: String, v: Any?): Any? {
            check(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDouble()
        }

        private fun str(): String {
            val out = StringBuilder()
            i++ // opening quote
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { out.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> out.append(e) // quote, backslash, slash
                }
            }
            i++ // closing quote
            return out.toString()
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                if (s[i++] == ']') return out
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                val k = str()
                ws()
                check(s[i++] == ':') { "expected ':' at ${i - 1}" }
                out[k] = value()
                ws()
                if (s[i++] == '}') return out
            }
        }
    }
}
