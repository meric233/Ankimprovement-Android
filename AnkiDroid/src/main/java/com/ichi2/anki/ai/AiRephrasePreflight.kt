// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import timber.log.Timber

/**
 * Held-out preflight evaluation (Android port of `run_preflight_eval` in
 * `qt/aqt/ai/rephrase.py`) — Speedrun §6 "an eval that runs before students see
 * anything: accuracy and wrong-answer rate on a held-out set, with your cutoff".
 *
 * It runs the frozen model on a fixed held-out Q/A set, logs the accuracy and
 * wrong-answer rate (visible via `adb logcat -s AiRephrase`), and GATES the
 * feature: rephrasing only turns on for students once the pre-declared cutoffs
 * are met. Runs on a background thread; never touches the UI.
 */
object AiRephrasePreflight {
    /** Held-out Q/A set (same items as the desktop `_BUILTIN_HOLDOUT`). These are
     *  intentionally NOT drawn from any real deck, so the eval is genuinely
     *  held-out from anything a student studies. */
    private val HOLDOUT: List<Triple<String, String, String>> =
        listOf(
            Triple("b01", "What enzyme is deficient in classic phenylketonuria?", "Phenylalanine hydroxylase"),
            Triple("b02", "Which vitamin deficiency causes Wernicke encephalopathy?", "Thiamine (B1)"),
            Triple("b03", "What ion channel is defective in cystic fibrosis?", "CFTR chloride channel"),
            Triple("b04", "Which neurotransmitter is decreased in Parkinson disease?", "Dopamine"),
            Triple("b05", "Which clotting factor is deficient in hemophilia B?", "Factor IX"),
        )

    fun run(config: AiConfig): PreflightResult {
        val items = HOLDOUT.take(AiRephrase.PREFLIGHT_MAX_ITEMS)
        val n = items.size
        Timber.i(
            "AI rephrase PREFLIGHT: checking %d held-out cards before any student sees a rephrase " +
                "(model=%s). Cutoffs: answer-preservation >= %.0f%% (wrong-answer-rate <= %.0f%%), " +
                "effective-rephrasing >= %.0f%%.",
            n,
            config.model,
            AiRephrase.PREFLIGHT_ANSWER_CUTOFF * 100,
            (1 - AiRephrase.PREFLIGHT_ANSWER_CUTOFF) * 100,
            AiRephrase.PREFLIGHT_EFFECTIVE_CUTOFF * 100,
        )
        if (n == 0) {
            Timber.w("AI rephrase PREFLIGHT: no held-out items; feature stays OFF.")
            return PreflightResult(0, 0.0, 1.0, 0.0, 0.0, 0.0, meaningVerified = false, passed = false)
        }

        var answerOk = 0
        var meaningOk = 0
        var worded = 0
        var effective = 0
        var meaningVerified = true

        for ((id, q, a) in items) {
            val out = AiRephraseApi.requestRephrasing(q, config)
            val aOk: Boolean
            val overlap: Double
            val wOk: Boolean
            var sim = 0.0
            if (out != null) {
                aOk = !out.lowercase().contains(a.lowercase())
                overlap = AiRephrase.lexicalOverlap(q, out)
                wOk = overlap < AiRephrase.PREFLIGHT_WORDING_MAX_OVERLAP
                val eq = AiRephraseApi.embedding(q, config)
                val eo = AiRephraseApi.embedding(out, config)
                sim =
                    if (eq != null && eo != null) {
                        AiRephrase.cosine(eq, eo)
                    } else {
                        meaningVerified = false
                        AiRephrase.lexicalOverlap(q, out)
                    }
            } else {
                aOk = false
                overlap = 1.0
                wOk = false
            }
            val mOk = out != null && sim >= AiRephrase.PREFLIGHT_SIM_CUTOFF
            val eff = aOk && mOk && wOk

            if (aOk) answerOk++
            if (mOk) meaningOk++
            if (wOk) worded++
            if (eff) effective++

            Timber.i(
                "AI rephrase PREFLIGHT  %-4s %s  answer=%s meaning=%.2f wording=%.2f\n    Q : %s\n    ->: %s",
                id,
                if (eff) "OK" else "--",
                if (aOk) "kept" else "LEAKED/BROKE",
                sim,
                overlap,
                AiRephrase.previewText(q),
                AiRephrase.previewText(out ?: "(no usable output)"),
            )
        }

        val accuracy = answerOk.toDouble() / n
        val effectiveRate = effective.toDouble() / n
        val passed =
            accuracy >= AiRephrase.PREFLIGHT_ANSWER_CUTOFF &&
                (!meaningVerified || effectiveRate >= AiRephrase.PREFLIGHT_EFFECTIVE_CUTOFF)
        val result =
            PreflightResult(
                n = n,
                accuracy = accuracy,
                wrongRate = 1.0 - accuracy,
                meaningRate = meaningOk.toDouble() / n,
                wordingChanged = worded.toDouble() / n,
                effectiveRate = effectiveRate,
                meaningVerified = meaningVerified,
                passed = passed,
            )
        Timber.i(
            "AI rephrase PREFLIGHT RESULT: %s — accuracy(answer-preservation)=%.0f%%, wrong-answer-rate=%.0f%%, " +
                "meaning-preservation=%.0f%%%s, effective-rephrasing=%.0f%% (cutoffs %.0f%% / %.0f%%). %s",
            if (passed) "PASS" else "FAIL",
            accuracy * 100,
            result.wrongRate * 100,
            result.meaningRate * 100,
            if (meaningVerified) "" else " (approx — embeddings unavailable, meaning not gated)",
            effectiveRate * 100,
            AiRephrase.PREFLIGHT_ANSWER_CUTOFF * 100,
            AiRephrase.PREFLIGHT_EFFECTIVE_CUTOFF * 100,
            if (passed) "Rephrasing is ENABLED for students." else "Rephrasing stays OFF for students until it passes.",
        )
        return result
    }
}
