package app.airshare

import android.app.Application
import app.airshare.connection.AirShareEngine
import app.airshare.data.HistoryRepository
import app.airshare.data.SettingsRepository

class AirShareApp : Application() {
    lateinit var settings: SettingsRepository; private set
    lateinit var history: HistoryRepository; private set
    lateinit var engine: AirShareEngine; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        history = HistoryRepository(this)
        engine = AirShareEngine(this, history)   // idle until used: no radios, no threads started here
    }
}
