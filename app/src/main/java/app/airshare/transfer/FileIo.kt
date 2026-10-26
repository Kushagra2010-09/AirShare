package app.airshare.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.airshare.core.FileNames
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class SourceFile(val uri: Uri, val name: String, val size: Long, val mime: String)

sealed interface SaveResult {
    data class Saved(val uri: String, val finalName: String) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

object FileIo {
    const val SUBDIR = "AirShare"
    private const val BUF = 64 * 1024

    /** Reads display name / size / type of shared content. Blocking: call from Dispatchers.IO. Skips unreadable items. */
    fun describe(context: Context, uris: List<Uri>): List<SourceFile> {
        val cr = context.contentResolver
        return uris.mapNotNull { uri ->
            runCatching {
                var name: String? = null
                var size = -1L
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                if (size < 0) cr.openFileDescriptor(uri, "r")?.use { size = it.statSize }
                if (size < 0) return@runCatching null
                val safe = FileNames.sanitize(name ?: uri.lastPathSegment)
                val mime = cr.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                SourceFile(uri, safe, size, mime)
            }.getOrNull()
        }
    }

    fun sha256(context: Context, uri: Uri): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(BUF)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        } ?: return null
        hex(md.digest())
    }.getOrNull()

    fun openPfd(context: Context, uri: Uri): ParcelFileDescriptor? =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun copyHashing(input: InputStream, out: OutputStream, md: MessageDigest): Long {
        val buf = ByteArray(BUF); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            md.update(buf, 0, n); out.write(buf, 0, n); total += n
        }
        out.flush()
        return total
    }

    /**
     * Streams the received payload into Download/AirShare while hashing it. The file only becomes visible
     * (IS_PENDING cleared / .part renamed) after size AND SHA-256 match, so corrupt or incomplete files never appear.
     */
    fun saveReceived(
        context: Context, pfd: ParcelFileDescriptor, rawName: String, mime: String,
        expectedSize: Long, expectedSha256: String,
    ): SaveResult {
        val name = FileNames.sanitize(rawName)
        val md = MessageDigest.getInstance("SHA-256")
        val safeMime = mime.ifBlank { "application/octet-stream" }
        return if (Build.VERSION.SDK_INT >= 29) saveQ(context, pfd, name, safeMime, expectedSize, expectedSha256, md)
        else saveLegacy(context, pfd, name, safeMime, expectedSize, expectedSha256, md)
    }

    private fun saveQ(
        context: Context, pfd: ParcelFileDescriptor, name: String, mime: String,
        size: Long, sha: String, md: MessageDigest,
    ): SaveResult {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return SaveResult.Failed("Couldn't create file")
        return try {
            val copied = cr.openOutputStream(uri)!!.use { out -> copyHashing(FileInputStream(pfd.fileDescriptor), out, md) }
            if (copied != size) { cr.delete(uri, null, null); return SaveResult.Failed("Incomplete file") }
            if (hex(md.digest()) != sha) { cr.delete(uri, null, null); return SaveResult.Failed("Integrity check failed") }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            SaveResult.Saved(uri.toString(), name)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            SaveResult.Failed("Couldn't save file")
        } finally { runCatching { pfd.close() } }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context, pfd: ParcelFileDescriptor, name: String, mime: String,
        size: Long, sha: String, md: MessageDigest,
    ): SaveResult {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return SaveResult.Failed("Storage unavailable")
        val finalName = FileNames.unique(name, dir.list()?.toSet() ?: emptySet())
        val part = File(dir, ".$finalName.part")
        val target = File(dir, finalName)
        return try {
            val copied = FileOutputStream(part).use { out -> copyHashing(FileInputStream(pfd.fileDescriptor), out, md) }
            if (copied != size) { part.delete(); return SaveResult.Failed("Incomplete file") }
            if (hex(md.digest()) != sha) { part.delete(); return SaveResult.Failed("Integrity check failed") }
            if (!part.renameTo(target)) { part.delete(); return SaveResult.Failed("Couldn't save file") }
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, target.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, size)
            }
            val uri = runCatching { context.contentResolver.insert(MediaStore.Files.getContentUri("external"), v) }.getOrNull()
            SaveResult.Saved((uri ?: Uri.fromFile(target)).toString(), finalName)
        } catch (e: Exception) {
            runCatching { part.delete() }
            SaveResult.Failed("Couldn't save file")
        } finally { runCatching { pfd.close() } }
    }
}
