package com.ruskcoder.messages.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.util.appJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val SETTINGS_KEY = stringPreferencesKey("settings.v1")

/**
 * The whole `Settings` object persists as one serialized blob under one DataStore key — the same
 * trade-off the RN app's synchronous KV store made: a handful of flags read on every launch, not
 * relational data. The first value is fetched with `runBlocking` at construction time so the very
 * first Compose frame already has the stored theme, with no flash of the defaults.
 */
class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(runBlocking { load() })
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        scope.launch {
            appContext.settingsStore.data.collect { prefs ->
                _settings.value = decode(prefs[SETTINGS_KEY])
            }
        }
    }

    private suspend fun load(): Settings = decode(appContext.settingsStore.data.first()[SETTINGS_KEY])

    private fun decode(raw: String?): Settings {
        if (raw.isNullOrBlank()) return Settings()
        return runCatching { appJson.decodeFromString(Settings.serializer(), raw) }.getOrDefault(Settings())
    }

    /** Applies `transform` immediately (so the UI never waits on disk) and persists behind it. */
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        scope.launch {
            appContext.settingsStore.edit { it[SETTINGS_KEY] = appJson.encodeToString(Settings.serializer(), next) }
        }
    }

    /**
     * Adopt a whole `Settings` object wholesale, as a restore does.
     *
     * Deliberately not expressed as `update { restored }`: that reads as an edit of what is there,
     * while this discards it. Anything the backup did not carry lands on its default, which is
     * what a restore from an older file should do rather than keeping a stale mixture.
     */
    fun replace(restored: Settings) = update { restored }

    fun reset() {
        _settings.value = Settings()
        scope.launch { appContext.settingsStore.edit { it.remove(SETTINGS_KEY) } }
    }
}
