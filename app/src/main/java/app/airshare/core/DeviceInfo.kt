package app.airshare.core

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceInfo {
    /** User-chosen name if set; otherwise the system device name if readable; otherwise the model; otherwise a generic label. */
    fun resolveName(context: Context, custom: String): String {
        val c = custom.trim()
        if (c.isNotEmpty()) return c.take(24)
        val sys = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        if (!sys.isNullOrBlank()) return sys.trim().take(24)
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotEmpty()) model.take(24) else "Android Device"
    }

    fun model(): String = (Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() } + " " + Build.MODEL.orEmpty()).trim().take(60)
}
