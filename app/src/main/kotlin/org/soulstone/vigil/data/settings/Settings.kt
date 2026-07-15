package org.soulstone.vigil.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.soulstone.vigil.model.Sensitivity

/**
 * Lightweight SharedPreferences-backed settings, exposed as StateFlows for
 * Compose. Deliberately tiny — no DataStore dependency for the prototype.
 */
class Settings private constructor(context: Context) {

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

    companion object {
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_ONBOARDED = "onboarded"

        @Volatile private var INSTANCE: Settings? = null
        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(context).also { INSTANCE = it }
        }
    }
}
