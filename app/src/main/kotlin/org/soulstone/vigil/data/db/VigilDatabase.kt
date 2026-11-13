package org.soulstone.vigil.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One tracked physical device, keyed by a best-effort stable identity. Carries
 * the running risk state plus the two "this is fine" signals: [approved] (user
 * allowlist) and [baselineSafe] (learned — routinely present at an anchor place).
 */
@Entity(tableName = "trackers")
data class TrackerEntity(
    @PrimaryKey val stableId: String,
    val ecosystem: String,
    val label: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val sightingCount: Int,
    val riskState: String,
    val approved: Boolean = false,
    val baselineSafe: Boolean = false,
    val lastAlertMs: Long = 0,
    // baseline accounting: distinct calendar-days this tracker was seen at an anchor place
    val lastAnchorDay: Long = -1,
    val anchorDayCount: Int = 0,
    // display / grounding: latest + peak RSSI and the current co-movement evidence
    val lastRssi: Int = 0,
    val peakRssi: Int = -127,
    val distinctPlaces: Int = 0,
    val effectiveSightings: Int = 0,
    // last-seen BLE MAC — needed to GATT-connect for "make it ring"
    val lastMac: String = "",
    // Only filled for the catch-all "any Bluetooth device" class (v5): the name the
    // device advertised, the maker its advert claimed, and its address kind
    // (AddressKind.code). All three are whatever the device said about itself.
    @ColumnInfo(defaultValue = "") val name: String = "",
    @ColumnInfo(defaultValue = "") val vendor: String = "",
    @ColumnInfo(defaultValue = "0") val addrKind: Int = 0
)

/** One sighting of a tracker at one instant, geotagged when a fix is available. */
@Entity(
    tableName = "sightings",
    indices = [Index(value = ["trackerId", "timestamp"])]
)
data class SightingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackerId: String,
    val timestamp: Long,
    val rssi: Int,
    val separated: Boolean,
    val lat: Double? = null,
    val lon: Double? = null,
    val geohash7: String? = null,
    // Claimed radius of the fix in metres (v5). Null on rows written before v5 and
    // when the provider gave none. Two sightings are only "different places" if
    // they are further apart than their fixes could be wrong by.
    val accuracy: Float? = null
)

/** A geohash-6 cell the user frequents; promoted to [anchor] once well-visited (e.g. home). */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey val geohash6: String,
    val label: String = "",
    val visitCount: Int = 0,
    val lastSeen: Long = 0,
    val anchor: Boolean = false
)

/** A recorded alert — when a tracker first crossed into ALERTING. Kept for the
 *  history list and the evidence export. */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackerId: String,
    val ecosystem: String,
    val label: String,
    val timestamp: Long,
    val distinctPlaces: Int,
    val peakRssi: Int,
    val lat: Double? = null,
    val lon: Double? = null
)

@Dao
interface TrackerDao {
    @Query("SELECT * FROM trackers WHERE stableId = :id")
    suspend fun get(id: String): TrackerEntity?

    @Upsert
    suspend fun upsert(tracker: TrackerEntity)

    @Query("SELECT * FROM trackers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<TrackerEntity>>

    /**
     * What the main screen needs: every tracker, but of the catch-all Bluetooth
     * devices only those that matter right now: heard since [since], marked by
     * the user, or flagged. A busy street mints hundreds of those rows an hour and
     * the screen must not re-read them all on every change.
     * ('ble:' .. 'ble;' is the prefix range for TrackerEcosystem.GENERIC_ID_PREFIX.)
     */
    @Query(
        "SELECT * FROM trackers WHERE NOT (stableId >= 'ble:' AND stableId < 'ble;') " +
            "OR approved = 1 OR riskState != 'OBSERVED' OR lastSeen >= :since " +
            "ORDER BY lastSeen DESC"
    )
    fun observeForUi(since: Long): Flow<List<TrackerEntity>>

    @Query("UPDATE trackers SET approved = :approved WHERE stableId = :id")
    suspend fun setApproved(id: String, approved: Boolean)

    /**
     * A known device was heard again but not worth a new sighting (see
     * [org.soulstone.vigil.detect.NearbyThrottle]). Keeps "last heard" and the
     * signal on screen truthful without touching the evidence. No-op for a device
     * with no row.
     */
    @Query(
        "UPDATE trackers SET lastSeen = :at, lastRssi = :rssi, peakRssi = MAX(peakRssi, :rssi) " +
            "WHERE stableId = :id AND lastSeen < :at"
    )
    suspend fun touch(id: String, at: Long, rssi: Int)

    // User says a baseline-trusted tag is "not mine" — drop trust and restart the
    // day clock so it doesn't immediately re-trust.
    @Query("UPDATE trackers SET baselineSafe = 0, anchorDayCount = 0, lastAnchorDay = -1 WHERE stableId = :id")
    suspend fun clearBaseline(id: String)

    // Rotated identities pile up (MAC rotation mints a new row each rotation); drop
    // stale, non-approved rows so the table and the UI list don't grow without bound.
    @Query("DELETE FROM trackers WHERE lastSeen < :cutoff AND approved = 0")
    suspend fun pruneStale(cutoff: Long)

    // Catch-all Bluetooth devices are far more numerous and far less interesting
    // than trackers, so they expire much sooner, unless the user marked one or it
    // has an alert in the history (then it stays as long as a tracker would, for
    // the record). Clearing the history ends that.
    @Query(
        "DELETE FROM trackers WHERE stableId >= 'ble:' AND stableId < 'ble;' " +
            "AND lastSeen < :cutoff AND approved = 0 " +
            "AND stableId NOT IN (SELECT trackerId FROM alerts)"
    )
    suspend fun pruneStaleGeneric(cutoff: Long)

    /**
     * Catch-all Bluetooth devices currently flagged and heard since [since]. Marked
     * ones are left out: the stored state of a device the user has just called
     * theirs stays ALERTING until its next sighting is written.
     */
    @Query(
        "SELECT * FROM trackers WHERE stableId >= 'ble:' AND stableId < 'ble;' " +
            "AND riskState = 'ALERTING' AND approved = 0 AND lastSeen >= :since " +
            "ORDER BY lastSeen DESC"
    )
    suspend fun alertingGeneric(since: Long): List<TrackerEntity>

    /** Catch-all Bluetooth devices whose stored state is above OBSERVED. */
    @Query(
        "SELECT stableId FROM trackers WHERE stableId >= 'ble:' AND stableId < 'ble;' " +
            "AND riskState != 'OBSERVED'"
    )
    suspend fun flaggedGenericIds(): List<String>

    @Query("DELETE FROM trackers")
    suspend fun clearAll()
}

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sighting: SightingEntity)

    @Query("SELECT * FROM sightings WHERE trackerId = :id AND timestamp >= :since ORDER BY timestamp")
    suspend fun recentFor(id: String, since: Long): List<SightingEntity>

    @Query("SELECT * FROM sightings WHERE trackerId = :id ORDER BY timestamp")
    suspend fun allFor(id: String): List<SightingEntity>

    /**
     * Geotagged sightings for specific trackers only. The evidence export used an
     * unscoped version of this, so the exported GPX was a 14-day movement log of the
     * user with every ambient tracker mixed in, rather than the flagged device's trail.
     */
    @Query("SELECT * FROM sightings WHERE lat IS NOT NULL AND trackerId IN (:ids) ORDER BY timestamp")
    suspend fun geotaggedFor(ids: List<String>): List<SightingEntity>

    @Query("DELETE FROM sightings WHERE timestamp < :cutoff")
    suspend fun prune(cutoff: Long)

    @Query(
        "DELETE FROM sightings WHERE trackerId >= 'ble:' AND trackerId < 'ble;' " +
            "AND timestamp < :cutoff AND trackerId NOT IN (SELECT trackerId FROM alerts)"
    )
    suspend fun pruneGeneric(cutoff: Long)

    @Query("DELETE FROM sightings")
    suspend fun clearAll()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE geohash6 = :cell")
    suspend fun get(cell: String): PlaceEntity?

    @Upsert
    suspend fun upsert(place: PlaceEntity)
}

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: AlertEntity)

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC")
    suspend fun all(): List<AlertEntity>

    @Query("SELECT MAX(timestamp) FROM alerts WHERE trackerId = :id")
    suspend fun lastAlertAt(id: String): Long?

    @Query("DELETE FROM alerts")
    suspend fun clear()
}

@Database(
    entities = [TrackerEntity::class, SightingEntity::class, PlaceEntity::class, AlertEntity::class],
    version = VigilDatabase.VERSION,
    // Exported to app/schemas; every version from 4 on has a real migration below.
    exportSchema = true
)
abstract class VigilDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao
    abstract fun sightingDao(): SightingDao
    abstract fun placeDao(): PlaceDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile private var INSTANCE: VigilDatabase? = null

        fun get(context: Context): VigilDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                VigilDatabase::class.java,
                "vigil.db"
            )
                .addMigrations(*MIGRATIONS)
                // The destructive fallback now only covers installs older than
                // schema 4, for which no migration was ever written. From 4 on,
                // an update keeps the alert history and sightings: a new version
                // MUST add its migration to MIGRATIONS (MigrationCoverageTest
                // fails the build if one is missing). The allowlist survives
                // either way; it lives in Settings (ApprovedStore).
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }

        /** 4 → 5: self-description columns for catch-all Bluetooth devices, and the
         *  accuracy of each sighting's fix. Adds columns only; nothing is rewritten. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trackers ADD COLUMN name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE trackers ADD COLUMN vendor TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE trackers ADD COLUMN addrKind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sightings ADD COLUMN accuracy REAL")
            }
        }

        /** Current schema version. Raising it means adding a migration below. */
        const val VERSION = 5

        /** Oldest schema version an update can come from without losing its data. */
        const val FIRST_MIGRATABLE_VERSION = 4

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5)
    }
}
