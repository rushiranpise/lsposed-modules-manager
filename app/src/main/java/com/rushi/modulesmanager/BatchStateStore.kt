package com.rushi.modulesmanager

import android.content.Context
import org.json.JSONObject

/** Stores the running update-all batch, so a restart can show what was interrupted. */
class BatchStateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("batch_state", Context.MODE_PRIVATE)

    fun load(): BatchUpdateState? = runCatching {
        val raw = prefs.getString(KEY, null) ?: return null
        val root = JSONObject(raw)
        val itemsJson = root.optJSONObject("items") ?: JSONObject()
        val items = buildMap {
            val keys = itemsJson.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val item = itemsJson.optJSONObject(packageName) ?: continue
                val phase = runCatching { BatchPhase.valueOf(item.optString("phase")) }
                    .getOrDefault(BatchPhase.QUEUED)
                put(
                    packageName,
                    BatchItemState(
                        title = item.optString("title").ifBlank { packageName },
                        phase = phase,
                        downloaded = item.optLong("downloaded", 0L),
                        total = item.optLong("total", 0L),
                        error = item.optString("error")
                            .takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
        if (items.isEmpty()) return null
        BatchUpdateState(
            running = root.optBoolean("running", false),
            total = root.optInt("total", items.size),
            current = root.optString("current").takeIf { it.isNotBlank() && it != "null" },
            items = items,
            interrupted = root.optBoolean("interrupted", false),
        )
    }.getOrNull()

    fun save(state: BatchUpdateState?) {
        if (state == null) {
            clear()
            return
        }
        val items = JSONObject()
        state.items.forEach { (packageName, item) ->
            items.put(packageName, JSONObject().apply {
                put("title", item.title)
                put("phase", item.phase.name)
                put("downloaded", item.downloaded)
                put("total", item.total)
                item.error?.let { put("error", it) }
            })
        }
        val root = JSONObject().apply {
            put("running", state.running)
            put("total", state.total)
            state.current?.let { put("current", it) }
            put("interrupted", state.interrupted)
            put("items", items)
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "batch"
    }
}
