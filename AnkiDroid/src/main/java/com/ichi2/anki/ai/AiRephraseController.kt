// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Collections

/**
 * Wires the pure AI-rephrase logic into the AnkiDroid reviewer, mirroring the
 * desktop `_RephraseController` (`qt/aqt/ai/rephrase.py`). Shared by both the
 * legacy reviewer ([com.ichi2.anki.Reviewer]) and the new reviewer
 * ([com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel]) so the feature
 * behaves identically regardless of which reviewer the user runs.
 *
 * Responsibilities:
 *  - gate rephrasing (feature on, learning mode, FSRS difficulty < 5, preflight passed);
 *  - substitute the reworded question (cache-first, network on miss);
 *  - prefetch upcoming cards in the background to hide latency;
 *  - on a rephrased answer, nudge the per-card perf score and damp the FSRS
 *    state change to 0.5x;
 *  - run the held-out preflight eval before any student is shown a rephrase.
 */
object AiRephraseController {
    private val config: AiConfig? by lazy { AiRephraseApi.loadConfig() }

    private var cache: AiRephraseCache? = null
    private var cacheDir: String? = null

    /** cardId -> (stability, difficulty) captured pre-answer when rephrased. */
    private val pending = Collections.synchronizedMap(HashMap<Long, Pair<Float, Float>>())

    /** cards whose rephrasing is being fetched in the background right now. */
    private val inflight = Collections.synchronizedSet(HashSet<Long>())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Legacy reviewer only: invoked on the main thread once an async fetch for
     * the *currently shown* card completes, so the reviewer can re-render the
     * question with the freshly-cached rephrasing. The new reviewer awaits the
     * fetch inline and does not need this.
     */
    @Volatile
    var onRephraseReady: ((cardId: Long) -> Unit)? = null

    /**
     * Legacy reviewer only: the card currently on screen. A background fetch (from
     * an on-demand miss *or* an earlier prefetch) re-renders the question the
     * moment its rewording lands iff it matches this. Needed because a card can
     * already be in-flight as a prefetch (isCurrent=false) by the time it becomes
     * current, in which case the on-demand fetch is deduped away and only this id
     * check can still trigger the swap.
     */
    @Volatile
    private var currentCardId: Long? = null

    // --- preflight state machine -----------------------------------------

    private enum class PreflightState { PENDING, RUNNING, PASSED, FAILED }

    @Volatile
    private var preflightState = PreflightState.PENDING
    private val preflightLock = Any()

    // --- cache -----------------------------------------------------------

    private fun cacheFor(col: Collection): AiRephraseCache? {
        val dir = col.colDb.parentFile?.absolutePath ?: return null
        if (cache == null || dir != cacheDir) {
            cache = AiRephraseCache(File(dir, "ai_rephrase_cache.json"))
            cacheDir = dir
        }
        return cache
    }

    // --- gating ----------------------------------------------------------

    private fun enabled(col: Collection): Boolean = config != null && col.config.get(AiRephrase.CONFIG_ENABLED, false) == true

    private fun learningMode(col: Collection): Boolean = col.config.get(AiRephrase.CONFIG_STUDY_MODE, "learning") == "learning"

    /** All gates from the desktop `should_rephrase`. Kicks the preflight off the
     *  first time a card would otherwise qualify. */
    fun shouldRephrase(
        col: Collection,
        card: Card,
    ): Boolean {
        if (!enabled(col) || !learningMode(col)) return false
        val difficulty = card.memoryState?.difficulty ?: return false // new card: no FSRS state
        if (difficulty >= AiRephrase.DIFFICULTY_THRESHOLD) return false
        ensurePreflight()
        return preflightState == PreflightState.PASSED
    }

    // --- question substitution -------------------------------------------

    /**
     * Legacy reviewer: synchronous, cache-only substitution (runs on the main
     * thread during rendering, so it must never block on the network). Returns
     * the reworded question if it is already cached, else null (keep original).
     * On a cache miss it kicks off a background fetch + prefetch; when the
     * current card's fetch lands, [onRephraseReady] re-renders it.
     */
    fun cachedQuestionForRender(
        col: Collection,
        card: Card,
        original: String,
    ): String? {
        if (!shouldRephrase(col, card)) return null
        // Mark this as the on-screen card so a completing fetch/prefetch knows to
        // re-render it (see [currentCardId]).
        currentCardId = card.id
        val c = cacheFor(col) ?: return null
        val sanitized = AiRephrase.sanitizeText(original)
        if (sanitized.isEmpty()) return null
        val hash = AiRephrase.sourceHash(sanitized)
        val record = c.get(card.id)
        val memoryState = card.memoryState ?: return null
        if (record != null && record.sourceHash == hash) {
            pending[card.id] = memoryState.stability to memoryState.difficulty
            Timber.i(
                "AI rephrase: SHOWING rephrased question for card %d NOW (this answer will be scored):\n" +
                    "  ORIGINAL  : %s\n  REPHRASED (on screen): %s",
                card.id,
                AiRephrase.previewText(sanitized),
                AiRephrase.previewText(record.text),
            )
            return record.text
        }
        // miss: fetch this card in the background (then re-render), and warm the
        // next few due cards so their first appearance is instant.
        scheduleFetch(c, card.id, card.nid, sanitized, hash, isCurrent = true)
        warmUpcoming()
        Timber.i("AI rephrase: card %d not cached yet — showing original, fetching in background", card.id)
        return null
    }

    /**
     * New reviewer: suspending substitution. Returns cached text, or fetches it
     * inline (off the main thread) on a miss, or the original on failure. Also
     * records the pre-answer state so the answer gets scored + damped.
     */
    suspend fun questionForDisplay(
        card: Card,
        original: String,
    ): String {
        val cfg = config ?: return original
        val sanitized = AiRephrase.sanitizeText(original)
        if (sanitized.isEmpty()) return original
        val hash = AiRephrase.sourceHash(sanitized)

        // gate + cache lookup while we hold the collection
        val gate =
            withCol {
                if (!shouldRephrase(this, card)) return@withCol null
                val c = cacheFor(this) ?: return@withCol null
                val ms = card.memoryState ?: return@withCol null
                val rec = c.get(card.id)
                Gate(c, ms.stability to ms.difficulty, if (rec != null && rec.sourceHash == hash) rec.text else null)
            } ?: return original

        gate.cachedText?.let { text ->
            pending[card.id] = gate.pre
            warmUpcoming()
            Timber.i("AI rephrase: SHOWING cached rephrase for card %d", card.id)
            return text
        }

        val out = withContext(Dispatchers.IO) { AiRephraseApi.requestRephrasing(sanitized, cfg) }
        warmUpcoming()
        if (out == null) {
            Timber.i("AI rephrase: no usable rewording for card %d; showing ORIGINAL (not scored)", card.id)
            pending.remove(card.id)
            return original
        }
        gate.cache.put(card.id, RephraseRecord(out, card.nid, hash, cfg.model, nowSecs()))
        pending[card.id] = gate.pre
        Timber.i(
            "AI rephrase: SHOWING rephrased question for card %d NOW (this answer will be scored):\n" +
                "  ORIGINAL  : %s\n  REPHRASED (on screen): %s",
            card.id,
            AiRephrase.previewText(sanitized),
            AiRephrase.previewText(out),
        )
        return out
    }

    private class Gate(
        val cache: AiRephraseCache,
        val pre: Pair<Float, Float>,
        val cachedText: String?,
    )

    private fun scheduleFetch(
        cache: AiRephraseCache,
        cardId: Long,
        noteId: Long,
        sanitized: String,
        hash: String,
        isCurrent: Boolean,
    ) {
        val cfg = config ?: return
        if (!inflight.add(cardId)) return
        scope.launch {
            try {
                Timber.i("AI rephrase: prefetch START (background) for card %d", cardId)
                val out = AiRephraseApi.requestRephrasing(sanitized, cfg)
                if (out != null) {
                    cache.put(cardId, RephraseRecord(out, noteId, hash, cfg.model, nowSecs()))
                    Timber.i(
                        "AI rephrase: CACHED new rewording for card %d:\n  ORIGINAL : %s\n  REPHRASED: %s",
                        cardId,
                        AiRephrase.previewText(sanitized),
                        AiRephrase.previewText(out),
                    )
                    // Re-render if this is the on-screen card — either because it
                    // was fetched on demand (isCurrent) or because a prefetch for it
                    // finished after it became current (cardId == currentCardId).
                    if (isCurrent || cardId == currentCardId) {
                        withContext(Dispatchers.Main) { onRephraseReady?.invoke(cardId) }
                    }
                } else {
                    Timber.i("AI rephrase: model returned no usable rewording for card %d", cardId)
                }
            } catch (ex: Exception) {
                Timber.d(ex, "AI rephrase: background fetch failed for card %d", cardId)
            } finally {
                inflight.remove(cardId)
            }
        }
    }

    /** Warm the next few due cards in the background (desktop `_warm_upcoming`). */
    fun warmUpcoming() {
        if (config == null) return
        scope.launch {
            try {
                withCol {
                    if (!enabled(this) || !learningMode(this)) return@withCol
                    val c = cacheFor(this) ?: return@withCol
                    val queued =
                        backend.getQueuedCards(
                            fetchLimit = AiRephrase.PREFETCH_AHEAD + 1,
                            intradayLearningOnly = false,
                        )
                    for (entry in queued.cardsList) {
                        val card = Card(entry.card)
                        if (c.get(card.id) != null || inflight.contains(card.id)) continue
                        if (!shouldRephrase(this, card)) continue
                        val sanitized = AiRephrase.sanitizeText(card.question(this))
                        if (sanitized.isNotEmpty()) {
                            scheduleFetch(c, card.id, card.nid, sanitized, AiRephrase.sourceHash(sanitized), isCurrent = false)
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.d(ex, "AI rephrase: warm-upcoming skipped")
            }
        }
    }

    // --- post-answer: perf nudge + damping + cache invalidation ----------

    /**
     * Called after `sched.answerCard`. If [cardId] was shown rephrased, nudge its
     * perf score by [ease] (1=Again..4=Easy) and damp the FSRS state change to
     * 0.5x, folding both into the answer's undo step. Runs inside a `withCol`.
     */
    fun onAnswered(
        col: Collection,
        cardId: Long,
        ease: Int,
    ) {
        val pre = pending.remove(cardId) ?: return // this card was not shown rephrased

        val target = col.undoStatus().lastStep // the "Answer Card" step
        val card = Card(col, cardId) // fresh post-answer state

        val current = AiRephrase.readPerf(card.customData)
        val updated = AiRephrase.nextPerf(current, ease)
        Timber.i(
            "AI rephrase: SCORING card %d perf %.1f -> %.1f (ease %d)",
            cardId,
            current ?: AiRephrase.PERF_DEFAULT,
            updated,
            ease,
        )
        card.customData = AiRephrase.withPerf(card.customData, updated)

        card.memoryState?.let { ms ->
            val (sOld, dOld) = pre
            card.memoryState =
                ms
                    .toBuilder()
                    .setStability(AiRephrase.damp(sOld, ms.stability))
                    .setDifficulty(AiRephrase.damp(dOld, ms.difficulty))
                    .build()
        }

        try {
            col.updateCard(card, skipUndoEntry = false)
            col.mergeUndoEntries(target)
        } catch (ex: Exception) {
            Timber.w(ex, "AI rephrase: failed to persist perf/damping for card %d", cardId)
        }

        if (ease == AiRephrase.EASY_EASE) {
            cacheFor(col)?.invalidate(cardId)
        }
    }

    // --- preflight -------------------------------------------------------

    private fun ensurePreflight() {
        val cfg = config ?: return
        synchronized(preflightLock) {
            if (preflightState != PreflightState.PENDING) return
            preflightState = PreflightState.RUNNING
        }
        scope.launch {
            val state =
                try {
                    if (AiRephrasePreflight.run(cfg).passed) PreflightState.PASSED else PreflightState.FAILED
                } catch (ex: Exception) {
                    Timber.w(ex, "AI rephrase PREFLIGHT: crashed; feature stays OFF.")
                    PreflightState.FAILED
                }
            synchronized(preflightLock) { preflightState = state }
        }
    }

    /** Re-run the held-out eval (e.g. when the feature is toggled back on) so its
     *  accuracy / wrong-answer-rate print to the log before any student sees a
     *  rephrase. Mirrors desktop `trigger_preflight`. */
    fun triggerPreflight(force: Boolean = false) {
        if (force) {
            synchronized(preflightLock) {
                if (preflightState != PreflightState.RUNNING) {
                    preflightState = PreflightState.PENDING
                }
            }
        }
        ensurePreflight()
    }

    private fun nowSecs(): Long = System.currentTimeMillis() / 1000
}
