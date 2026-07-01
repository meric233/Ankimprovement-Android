// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection

/**
 * USMLE project: SPOV2 difficulty-gated font randomization.
 *
 * On *easy* cards (low FSRS difficulty) the card font is swapped to a
 * noticeably-different, slightly-harder-to-read face. This strips the
 * "familiar font" environmental cue and adds a small desirable difficulty that
 * forces deeper encoding. On hard cards, and on brand-new cards that don't have
 * an FSRS difficulty yet, the default font is kept (extra visual load on hard
 * material backfires). The whole feature is gated on the shared study-mode
 * toggle ([STUDY_MODE_KEY]) being in long-term learning mode.
 *
 * An instance is stateful: it remembers the font picked for a card so the
 * question and answer sides share one face, re-rolling only when a new question
 * is shown.
 */
class UsmleFontChooser {
    private var chosen: Pair<Long, String?>? = null

    /**
     * A CSS rule forcing an alternate font for [card], or `null` to keep the
     * default. Pass [reroll] = true on the question side (pick a new font) and
     * false on the answer side (reuse the question's font).
     */
    fun cssFor(
        col: Collection,
        card: Card,
        reroll: Boolean,
    ): String? {
        val fontStack = fontStackFor(col, card, reroll) ?: return null
        // Target the card content with !important so it wins over the note
        // type's own `.card { font-family: ... }`.
        return "#qa, #qa .card { font-family: $fontStack !important; }"
    }

    private fun fontStackFor(
        col: Collection,
        card: Card,
        reroll: Boolean,
    ): String? {
        val learningMode = col.config.get<String>(STUDY_MODE_KEY, "learning") == "learning"
        if (!learningMode) return null

        // Only reviewed cards have an FSRS difficulty; new cards keep default.
        val difficulty = card.memoryState?.difficulty ?: return null
        if (difficulty >= DIFFICULTY_THRESHOLD) return null

        chosen?.let { (id, font) ->
            if (!reroll && id == card.id) return font
        }
        val font = FONT_STACKS.random()
        chosen = card.id to font
        return font
    }

    companion object {
        /**
         * Shared study-mode config key (SPOV1). "learning" (default) enables
         * desirable-difficulty features; "performance" turns them off. Stored in
         * collection config so it syncs across devices and matches the desktop.
         */
        const val STUDY_MODE_KEY = "usmleStudyMode"

        /** Cards with FSRS difficulty below this get an alternate font. */
        private const val DIFFICULTY_THRESHOLD = 5.0f

        /**
         * Alternate font stacks for easy cards. Each ends in a CSS generic
         * family (cursive / fantasy / monospace) so something clearly different
         * from the default sans-serif renders on every device even when a named
         * face is missing.
         */
        private val FONT_STACKS =
            listOf(
                "'Comic Sans MS', 'Comic Neue', 'Chalkboard SE', 'Chilanka', cursive",
                "'Courier New', 'Courier', 'DejaVu Sans Mono', monospace",
                "'Papyrus', 'Herculanum', 'Luminari', fantasy",
                "'Brush Script MT', 'Segoe Script', 'Snell Roundhand', cursive",
            )
    }
}
