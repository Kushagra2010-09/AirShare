package app.airshare.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.Manifest
import android.os.Build
import android.os.ParcelUuid
import app.airshare.core.Permissions
import java.util.UUID

/**
 * Tiny BLE "beacon" used ONLY for coarse proximity ranking. Receivers advertise a 3-byte random token
 * (also embedded in their Nearby endpoint name); senders scan for it for the short search window and read RSSI.
 * Nearby Connections does not expose RSSI, so this is the only source of signal strength.
 * The token is NOT an identity: it can be copied by anyone, so it only ever influences a suggestion,
 * never who receives the files.
 */
class BleBeacon(private val context: Context) {
    companion object {
        val SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("7e3a0c10-5a1f-4b6e-9d52-a1c0a1d5a1e5"))
        const val TOKEN_BYTES = 3
        fun tokenHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var advCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private fun canAdvertise() = if (Build.VERSION.SDK_INT >= 31) Permissions.has(context, Manifest.permission.BLUETOOTH_ADVERTISE) else true
    private fun canScan() = if (Build.VERSION.SDK_INT >= 31) Permissions.has(context, Manifest.permission.BLUETOOTH_SCAN)
        else Permissions.has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** True when the radio is on and the chipset can run a second advertiser next to Nearby's. */
    @SuppressLint("MissingPermission")
    fun advertiseSupported(): Boolean = runCatching {
        val a = adapter; a != null && a.isEnabled && a.isMultipleAdvertisementSupported && a.bluetoothLeAdvertiser != null
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun scanSupported(): Boolean = runCatching { val a = adapter; a != null && a.isEnabled && a.bluetoothLeScanner != null }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun startAdvertising(token: ByteArray): Boolean {
        if (!canAdvertise() || !advertiseSupported() || advCallback != null) return advCallback != null
        val adv = adapter?.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE_UUID, token).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { advCallback = null }
        }
        return runCatching { adv.startAdvertising(settings, data, cb); advCallback = cb; true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val cb = advCallback ?: return
        advCallback = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    /** Starts a short, filtered, low-latency scan. Always pair with [stopScan]. */
    @SuppressLint("MissingPermission")
    fun startScan(onReading: (token: String, rssi: Int) -> Unit): Boolean {
        if (!canScan() || !scanSupported() || scanCallback != null) return scanCallback != null
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder()
            .setServiceData(SERVICE_UUID, ByteArray(TOKEN_BYTES), ByteArray(TOKEN_BYTES)) // mask 0 = any token
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.scanRecord?.getServiceData(SERVICE_UUID) ?: return
                if (d.size == TOKEN_BYTES) onReading(tokenHex(d), result.rssi)
            }
        }
        return runCatching { scanner.startScan(listOf(filter), settings, cb); scanCallback = cb; true }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
