package app.airshare.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore("airshare_settings")

data class AppSettings(
    val displayName: String = "",
    val receivingEnabled: Boolean = false,
    val pauseWhenScreenOff: Boolean = false,
    val proximityBeacon: Boolean = true,
    val theme: String = "system",          // system | light | dark
    val dynamicColor: Boolean = false,
    val inviteTimeoutSec: Int = 90,
    val showProgressNotification: Boolean = true,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("display_name")
        val receiving = booleanPreferencesKey("receiving")
        val pauseOff = booleanPreferencesKey("pause_screen_off")
        val beacon = booleanPreferencesKey("proximity_beacon")
        val theme = stringPreferencesKey("theme")
        val dyn = booleanPreferencesKey("dynamic_color")
        val timeout = intPreferencesKey("invite_timeout")
        val progress = booleanPreferencesKey("progress_notification")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            displayName = p[K.name] ?: "",
            receivingEnabled = p[K.receiving] ?: false,
            pauseWhenScreenOff = p[K.pauseOff] ?: false,
            proximityBeacon = p[K.beacon] ?: true,
            theme = p[K.theme] ?: "system",
            dynamicColor = p[K.dyn] ?: false,
            inviteTimeoutSec = (p[K.timeout] ?: 90).coerceIn(20, 600),
            showProgressNotification = p[K.progress] ?: true,
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setName(v: String) = context.store.edit { it[K.name] = v.trim().take(24) }
    suspend fun setReceiving(v: Boolean) = context.store.edit { it[K.receiving] = v }
    suspend fun setPauseWhenScreenOff(v: Boolean) = context.store.edit { it[K.pauseOff] = v }
    suspend fun setProximityBeacon(v: Boolean) = context.store.edit { it[K.beacon] = v }
    suspend fun setTheme(v: String) = context.store.edit { it[K.theme] = v }
    suspend fun setDynamicColor(v: Boolean) = context.store.edit { it[K.dyn] = v }
    suspend fun setInviteTimeout(v: Int) = context.store.edit { it[K.timeout] = v.coerceIn(20, 600) }
    suspend fun setProgressNotification(v: Boolean) = context.store.edit { it[K.progress] = v }
}
