// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * USMLE project — pure logic for AI rephrasing of card questions during review.
 *
 * This is the Android (Kotlin) port of the desktop module
 * `qt/aqt/ai/rephrase.py`. It keeps the network-free, Anki-free logic (perf
 * math, FSRS damping, sanitising, prompt building, response parsing, the
 * held-out preflight scoring) in one testable place; the OpenAI calls live in
 * [AiRephraseApi] and the reviewer wiring in [AiRephraseController].
 *
 * Behaviour mirrors the desktop feature exactly so a card scored on the phone
 * and on the desktop moves the same way and the dashboard numbers agree.
 */
object AiRephrase {
    // --- Config keys (shared with desktop via collection config, so they sync)

    /** Collection-config key: master on/off switch (default false). */
    const val CONFIG_ENABLED = "aiRephraseEnabled"

    /** Collection-config key for the study-mode toggle (shared with the font
     *  feature). "learning" == long-term learning mode. */
    const val CONFIG_STUDY_MODE = "usmleStudyMode"

    // --- Per-card performance score --------------------------------------

    /** custom_data key for the per-card performance score. */
    const val PERF_KEY = "perf"
    const val PERF_DEFAULT = 50.0
    const val PERF_MIN = 1.0
    const val PERF_MAX = 100.0

    /** Grade -> performance delta (Again, Hard, Good, Easy). Arbitrary v1 steps,
     *  identical to the desktop PERF_STEPS. */
    val PERF_STEPS: Map<Int, Double> = mapOf(1 to -8.0, 2 to -3.0, 3 to 3.0, 4 to 8.0)

    /** Fraction of the normal FSRS state change applied on a rephrased answer. */
    const val DAMPING_K = 0.5

    /** Only rephrase cards easier than this FSRS difficulty (reuse the font gate). */
    const val DIFFICULTY_THRESHOLD = 5.0f

    /** How many upcoming due cards to warm (rephrase in the background) each
     *  time a question is shown, so first appearance is instant. */
    const val PREFETCH_AHEAD = 4

    /** Easy button ease value. */
    const val EASY_EASE = 4

    const val DEFAULT_MODEL = "gpt-4o"

    // --- Preflight held-out eval cutoffs (mirror the desktop values) ------

    const val PREFLIGHT_ANSWER_CUTOFF = 0.90 // answer-preservation (wrong-rate <= 10%)
    const val PREFLIGHT_EFFECTIVE_CUTOFF = 0.80 // meaning kept AND wording changed
    const val PREFLIGHT_SIM_CUTOFF = 0.82 // embedding-cosine meaning threshold
    const val PREFLIGHT_WORDING_MAX_OVERLAP = 0.9 // overlap below this == wording changed
    const val PREFLIGHT_MAX_ITEMS = 15

    val SYSTEM_PROMPT =
        """
        You restructure the question side of a spaced-repetition flashcard so it tests the exact same fact with a noticeably different sentence structure but the same vocabulary. Follow every rule strictly:
        (1) Change the STRUCTURE aggressively: convert active<->passive, reorder clauses, move the interrogative, and it is fine to be wordier or to split one sentence into two. E.g. 'Which drug can treat A?' -> 'A can be treated with which drug?' or 'A is a disease. Which drug treats it?'
        (2) Use the SAME words: you may only reuse the original's words or swap in very close synonyms. Do NOT introduce any new concept, qualifier, or claim that is not already there. In particular never add words like 'effective', 'effective against', 'initiate', 'utilize', 'management', 'first-line'. 'used to treat' may be reordered (e.g. 'is used to treat', 'to treat X, which drug is used') but must NOT become 'is effective against' or 'is the treatment for' — that changes the claim.
        (3) Preserve the meaning and the exact logical relationship EXACTLY. Never strengthen, weaken, generalize, or narrow the statement, and never answer it or add information.
        (4) Keep verbatim, unchanged: the answer, all medical/technical terms, drug and disease names, numbers, units, abbreviations, cloze markers (e.g. {{c1::...}}), the '[...]' / '[which ...?]' blanks, and every HTML tag, attribute, image, audio reference, and hashtag/tag string.
        (5) The result must still be a question (or the same set of cloze prompts).
        (6) Output only the reworded card text, nothing else.
        """.trimIndent()

    // --- Per-card performance math (pure) --------------------------------

    fun clampPerf(value: Double): Double = max(PERF_MIN, min(PERF_MAX, value))

    /** Current perf score, or null if the card was never scored. */
    fun readPerf(customData: String): Double? {
        val data = loadCustomData(customData)
        if (!data.has(PERF_KEY)) return null
        val raw = data.opt(PERF_KEY)
        val num = (raw as? Number)?.toDouble() ?: return null
        return clampPerf(num)
    }

    /** Nudge a card's perf by the grade. Unknown -> start from the default. */
    fun nextPerf(
        current: Double?,
        ease: Int,
    ): Double {
        val base = current ?: PERF_DEFAULT
        return clampPerf(base + (PERF_STEPS[ease] ?: 0.0))
    }

    /** Return the custom_data JSON string with `perf` set to `value`. */
    fun withPerf(
        customData: String,
        value: Double,
    ): String {
        val data = loadCustomData(customData)
        // round to 1 dp, matching desktop, to stay within the 100-byte limit.
        val rounded = Math.round(clampPerf(value) * 10.0) / 10.0
        data.put(PERF_KEY, rounded)
        return data.toString()
    }

    private fun loadCustomData(customData: String): JSONObject =
        if (customData.isEmpty()) {
            JSONObject()
        } else {
            try {
                JSONObject(customData)
            } catch (_: Exception) {
                JSONObject()
            }
        }

    // --- FSRS damping (pure) ---------------------------------------------

    /** Blend a memory-state value toward the post-answer value by fraction k. */
    fun damp(
        old: Float,
        new: Float,
        k: Double = DAMPING_K,
    ): Float = (old + k * (new - old)).toFloat()

    // --- Sanitising & source tracing (pure) ------------------------------

    private val SCRIPT_RE = Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE_RE = Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT_RE = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val ON_ATTR_RE = Regex("\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val HIDDEN_RE =
        Regex(
            "<[^>]*(?:display\\s*:\\s*none|visibility\\s*:\\s*hidden|hidden\\b)[^>]*>",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Strip script/style/comments, event handlers and hidden nodes before any
     * note text is sent to the model (defends prompt-injection via hidden text).
     */
    fun sanitizeText(text: String): String {
        var t = text
        t = SCRIPT_RE.replace(t, "")
        t = STYLE_RE.replace(t, "")
        t = COMMENT_RE.replace(t, "")
        t = HIDDEN_RE.replace(t, "")
        t = ON_ATTR_RE.replace(t, "")
        return t.trim()
    }

    private val TAG_RE = Regex("<[^>]+>")
    private val WS_RE = Regex("\\s+")

    /** Collapse HTML/whitespace into one readable line for demo logging. */
    fun previewText(
        text: String,
        limit: Int = 400,
    ): String {
        var t = TAG_RE.replace(text, " ")
        t = WS_RE.replace(t, " ").trim()
        if (t.length > limit) t = t.substring(0, limit - 1) + "\u2026"
        return t
    }

    fun sourceHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    /** Cheap runtime guard: reject empty/degenerate model output. */
    fun plausibleRephrasing(
        original: String,
        candidate: String,
    ): Boolean {
        val c = candidate.trim()
        if (c.isEmpty()) return false
        val o = original.trim().length
        if (o > 0 && (c.length < 0.3 * o || c.length > 3.0 * o)) return false
        return true
    }

    // --- Preflight scoring helpers (pure) --------------------------------

    private val EVAL_WORD_RE = Regex("[a-z0-9]+")

    fun lexicalOverlap(
        a: String,
        b: String,
    ): Double {
        val ta = EVAL_WORD_RE.findAll(a.lowercase()).map { it.value }.toSet()
        val tb = EVAL_WORD_RE.findAll(b.lowercase()).map { it.value }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return inter / union
    }

    fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        val n = min(a.size, b.size)
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0.0
    }
}

/** Source-traced cache record (mirrors desktop RephraseRecord). */
data class RephraseRecord(
    val text: String,
    val noteId: Long,
    val sourceHash: String,
    val model: String,
    val created: Long,
)

/** Held-out preflight eval outcome (mirrors desktop PreflightResult). */
data class PreflightResult(
    val n: Int,
    val accuracy: Double,
    val wrongRate: Double,
    val meaningRate: Double,
    val wordingChanged: Double,
    val effectiveRate: Double,
    val meaningVerified: Boolean,
    val passed: Boolean,
)
