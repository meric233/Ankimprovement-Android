// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import com.ichi2.anki.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Resolved OpenAI key + model (Android equivalent of desktop `AiConfig`). */
data class AiConfig(
    val apiKey: String,
    val model: String,
)

/**
 * OpenAI network layer for the AI-rephrase feature (Android port of the
 * `request_rephrasing` / `_embedding` / `run_preflight_eval` functions in
 * `qt/aqt/ai/rephrase.py`).
 *
 * The key is injected at build time from `local.properties` (`OPENAI_API_KEY`)
 * or the `OPENAI_API_KEY` env var into [BuildConfig] — the same key the desktop
 * reads from `ai_secrets.json`. If no key is present the feature stays OFF, and
 * the app still scores fine without AI (0.9 * memory fallback on the dashboard).
 */
object AiRephraseApi {
    private const val CHAT_URL = "https://api.openai.com/v1/chat/completions"
    private const val EMBED_URL = "https://api.openai.com/v1/embeddings"
    private const val EMBED_MODEL = "text-embedding-3-small"
    private const val TIMEOUT_SECONDS = 20L

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Resolve the key/model, or null if no key was compiled in. */
    fun loadConfig(): AiConfig? {
        val key = BuildConfig.OPENAI_API_KEY
        if (key.isNullOrBlank()) return null
        val model = BuildConfig.OPENAI_MODEL.ifBlank { AiRephrase.DEFAULT_MODEL }
        return AiConfig(apiKey = key, model = model)
    }

    /**
     * Call OpenAI to reword [text]. Returns null on any failure (offline, error,
     * timeout, malformed or degenerate output) so callers fall back to original.
     * Blocking — must be called off the main thread.
     */
    fun requestRephrasing(
        text: String,
        config: AiConfig,
    ): String? {
        val body =
            JSONObject()
                .put("model", config.model)
                .put("temperature", 0.4)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", AiRephrase.SYSTEM_PROMPT))
                        .put(JSONObject().put("role", "user").put("content", text)),
                ).toString()
        val candidate =
            post(CHAT_URL, body, config)?.let { json ->
                try {
                    json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } catch (ex: Exception) {
                    Timber.d(ex, "AiRephrase: could not parse completion")
                    null
                }
            } ?: return null
        return if (AiRephrase.plausibleRephrasing(text, candidate)) candidate else null
    }

    /** Embedding vector for [text], or null on failure (caller falls back to
     *  lexical similarity). Blocking — call off the main thread. */
    fun embedding(
        text: String,
        config: AiConfig,
    ): FloatArray? {
        val body =
            JSONObject()
                .put("model", EMBED_MODEL)
                .put("input", text)
                .toString()
        return post(EMBED_URL, body, config)?.let { json ->
            try {
                val arr = json.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun post(
        url: String,
        jsonBody: String,
        config: AiConfig,
    ): JSONObject? =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .post(jsonBody.toRequestBody(JSON))
                    .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.i("AiRephrase: request to %s failed: HTTP %d", url, resp.code)
                    null
                } else {
                    JSONObject(resp.body.string())
                }
            }
        } catch (ex: Exception) {
            Timber.i(ex, "AiRephrase: request to %s failed", url)
            null
        }
}
