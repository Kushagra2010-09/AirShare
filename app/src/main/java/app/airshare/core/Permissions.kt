package app.airshare.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.location.LocationManager

object Permissions {
    /** Runtime permissions needed for Nearby Connections on this Android version (per official docs). */
    fun nearbyRuntime(): List<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            Build.VERSION.SDK_INT >= 31 -> {
                add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            else -> {
                add(Manifest.permission.ACCESS_FINE_LOCATION)   // required for BLE/Wi-Fi discovery before Android 12
                if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    fun missing(context: Context): List<String> =
        nearbyRuntime().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** POST_NOTIFICATIONS is optional for operation (the foreground-service notice may be hidden), but recommended. */
    fun missingRequired(context: Context): List<String> =
        missing(context).filter { it != Manifest.permission.POST_NOTIFICATIONS }

    fun has(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun bluetoothOn(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        @SuppressLint("MissingPermission")
        val on = runCatching { bm?.adapter?.isEnabled == true }.getOrDefault(false)
        return on
    }

    fun powerSave(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Before Android 12 the system requires the Location switch to be ON for BLE / Wi-Fi discovery (no GPS use by AirShare). */
    fun locationSwitchOk(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }
}
