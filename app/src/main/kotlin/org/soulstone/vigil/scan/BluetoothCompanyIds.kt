package org.soulstone.vigil.scan

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * Bluetooth SIG company identifiers → maker name, so an unnamed device can at least
 * be shown as "Bose device" rather than a bare address. Bundled as an asset
 * (VIGIL is offline) generated from the SIG's public Assigned Numbers list by
 * tools/sig2tsv.py.
 *
 * The id is whatever the advert claims, and anyone can put any id in an advert, so
 * this is a display hint only. Nothing is ever trusted or matched on it.
 */
object BluetoothCompanyIds {
    private const val TAG = "BluetoothCompanyIds"
    const val ASSET = "bt_company_ids.tsv"

    @Volatile private var table: Map<Int, String>? = null

    /** Parse the `HEX<TAB>name` asset. Lines starting with '#' are comments. */
    fun parse(input: InputStream): Map<Int, String> {
        val out = HashMap<Int, String>(8192)
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val tab = line.indexOf('\t')
                if (tab != 4) continue
                val id = line.substring(0, 4).toIntOrNull(16) ?: continue
                val name = line.substring(5).trim()
                if (name.isNotEmpty()) out[id] = name
            }
        }
        return out
    }

    /** Load once; a missing or unreadable asset just means no maker names. */
    fun ensureLoaded(context: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            table = runCatching { context.applicationContext.assets.open(ASSET).use(::parse) }
                .onFailure { Log.w(TAG, "company ids unavailable: ${it.message}") }
                .getOrDefault(emptyMap())
        }
    }

    /** For tests, which have no asset manager. */
    fun setTableForTest(t: Map<Int, String>?) { table = t }

    fun name(companyId: Int): String = table?.get(companyId and 0xFFFF) ?: ""
}
