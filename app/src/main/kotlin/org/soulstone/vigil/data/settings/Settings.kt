package org.soulstone.vigil.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.soulstone.vigil.data.ApprovedStore
import org.soulstone.vigil.model.Sensitivity

/**
 * Lightweight SharedPreferences-backed settings, exposed as StateFlows for
 * Compose. Deliberately tiny — no DataStore dependency for the prototype.
 */
class Settings private constructor(context: Context) : ApprovedStore {

    private val prefs = context.applicationContext.getSharedPreferences("vigil", Context.MODE_PRIVATE)

    private val _sensitivity = MutableStateFlow(
        runCatching { Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, null) ?: "") }
            .getOrDefault(Sensitivity.MEDIUM)
    )
    val sensitivity: StateFlow<Sensitivity> = _sensitivity.asStateFlow()

    fun setSensitivity(value: Sensitivity) {
        prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()
        _sensitivity.value = value
    }

    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()
    fun setOnboarded(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
        _onboarded.value = value
    }

    /**
     * The allowlist, kept here rather than only in Room because the database falls
     * back to destructive migration — a schema bump would otherwise silently
     * un-approve the user's own trackers and start alerting on them again.
     */
    override fun isApproved(id: String): Boolean =
        prefs.getStringSet(KEY_APPROVED, emptySet())?.contains(id) == true

    override fun setApproved(id: String, approved: Boolean) {
        val current = prefs.getStringSet(KEY_APPROVED, emptySet()) ?: emptySet()
        val next = if (approved) current + id else current - id
        if (next == current) return
        // Copy: SharedPreferences returns a live set that must not be mutated.
        prefs.edit().putStringSet(KEY_APPROVED, HashSet(next)).apply()
    }

    companion object {
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_APPROVED = "approved_ids"

        @Volatile private var INSTANCE: Settings? = null
        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(context).also { INSTANCE = it }
        }
    }
}
