package app.airshare.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyStore: DataStore<Preferences> by preferencesDataStore("airshare_history")

data class HistoryEntry(
    val id: String,
    val timeMs: Long,
    val sent: Boolean,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val ok: Boolean,
    val firstName: String,
    val openUri: String?,      // content Uri of the first received file (received entries only)
    val mime: String?,
)

/** Small capped JSON list in DataStore: avoids pulling in Room for a 50-row history. */
class HistoryRepository(private val context: Context) {
    private val key = stringPreferencesKey("entries")
    private val cap = 50

    val flow: Flow<List<HistoryEntry>> = context.historyStore.data.map { parse(it[key]) }

    suspend fun add(e: HistoryEntry) {
        context.historyStore.edit { p ->
            val list = (listOf(e) + parse(p[key]).filterNot { it.id == e.id }).take(cap)
            p[key] = encode(list)
        }
    }

    suspend fun clear() { context.historyStore.edit { it.remove(key) } }
    suspend fun snapshot(): List<HistoryEntry> = flow.first()

    private fun encode(list: List<HistoryEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id); put("t", e.timeMs); put("sent", e.sent); put("peer", e.peerName)
                put("n", e.fileCount); put("b", e.totalBytes); put("ok", e.ok); put("f", e.firstName)
                put("u", e.openUri ?: JSONObject.NULL); put("m", e.mime ?: JSONObject.NULL)
            })
        }
    }.toString()

    private fun parse(s: String?): List<HistoryEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                HistoryEntry(
                    o.getString("id"), o.getLong("t"), o.getBoolean("sent"), o.getString("peer"),
                    o.getInt("n"), o.getLong("b"), o.getBoolean("ok"), o.getString("f"),
                    if (o.isNull("u")) null else o.getString("u"), if (o.isNull("m")) null else o.getString("m"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
