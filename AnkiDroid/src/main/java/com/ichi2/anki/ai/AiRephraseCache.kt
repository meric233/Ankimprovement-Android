// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * On-disk cache keyed by card id (Android port of the desktop `RephraseCache`).
 * A record stays until it is invalidated (on an Easy grade), so a card shows one
 * stable rephrasing until then. Stored in the collection folder so it is
 * per-profile, just like the desktop `{profileFolder}/ai_rephrase_cache.json`.
 */
class AiRephraseCache(
    private val file: File,
) {
    private val records = HashMap<String, RephraseRecord>()
    private val lock = Any()

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText(Charsets.UTF_8))
            for (key in root.keys()) {
                val o = root.getJSONObject(key)
                records[key] =
                    RephraseRecord(
                        text = o.getString("text"),
                        noteId = o.optLong("noteId"),
                        sourceHash = o.optString("sourceHash"),
                        model = o.optString("model"),
                        created = o.optLong("created"),
                    )
            }
        } catch (ex: Exception) {
            Timber.d(ex, "AiRephrase: could not read cache")
        }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            for ((k, v) in records) {
                root.put(
                    k,
                    JSONObject()
                        .put("text", v.text)
                        .put("noteId", v.noteId)
                        .put("sourceHash", v.sourceHash)
                        .put("model", v.model)
                        .put("created", v.created),
                )
            }
            file.writeText(root.toString(), Charsets.UTF_8)
        } catch (ex: Exception) {
            Timber.w(ex, "AiRephrase: could not persist cache")
        }
    }

    fun get(cardId: Long): RephraseRecord? =
        synchronized(lock) {
            records[cardId.toString()]
        }

    fun put(
        cardId: Long,
        record: RephraseRecord,
    ) {
        synchronized(lock) {
            records[cardId.toString()] = record
            save()
        }
    }

    fun invalidate(cardId: Long) {
        synchronized(lock) {
            if (records.remove(cardId.toString()) != null) save()
        }
    }
}
