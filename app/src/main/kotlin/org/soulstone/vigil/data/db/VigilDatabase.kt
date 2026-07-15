package org.soulstone.vigil.data.db

import android.content.Context
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
    val lastMac: String = ""
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
    val geohash7: String? = null
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

@Dao
interface TrackerDao {
    @Query("SELECT * FROM trackers WHERE stableId = :id")
    suspend fun get(id: String): TrackerEntity?

    @Upsert
    suspend fun upsert(tracker: TrackerEntity)

    @Query("SELECT * FROM trackers ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<TrackerEntity>>

    @Query("UPDATE trackers SET approved = :approved WHERE stableId = :id")
    suspend fun setApproved(id: String, approved: Boolean)

    // User says a baseline-trusted tag is "not mine" — drop trust and restart the
    // day clock so it doesn't immediately re-trust.
    @Query("UPDATE trackers SET baselineSafe = 0, anchorDayCount = 0, lastAnchorDay = -1 WHERE stableId = :id")
    suspend fun clearBaseline(id: String)

    // Rotated identities pile up (MAC rotation mints a new row each rotation); drop
    // stale, non-approved rows so the table and the UI list don't grow without bound.
    @Query("DELETE FROM trackers WHERE lastSeen < :cutoff AND approved = 0")
    suspend fun pruneStale(cutoff: Long)

    @Query("DELETE FROM trackers")
    suspend fun clearAll()
}

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sighting: SightingEntity)

    @Query("SELECT * FROM sightings WHERE trackerId = :id AND timestamp >= :since ORDER BY timestamp")
    suspend fun recentFor(id: String, since: Long): List<SightingEntity>

    @Query("DELETE FROM sightings WHERE timestamp < :cutoff")
    suspend fun prune(cutoff: Long)

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

@Database(
    entities = [TrackerEntity::class, SightingEntity::class, PlaceEntity::class],
    version = 3,
    exportSchema = false
)
abstract class VigilDatabase : RoomDatabase() {
    abstract fun trackerDao(): TrackerDao
    abstract fun sightingDao(): SightingDao
    abstract fun placeDao(): PlaceDao

    companion object {
        @Volatile private var INSTANCE: VigilDatabase? = null

        fun get(context: Context): VigilDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                VigilDatabase::class.java,
                "vigil.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
