// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import anki.stats.AdminAdvanceDaysRequest
import anki.stats.AdminOpResponse
import anki.stats.AdminResetCardsRequest
import anki.stats.AdminSetFsrsRequest
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.destinations.AdminSimulationDestination
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.stats.adminAdvanceDaysRaw
import com.ichi2.anki.libanki.stats.adminResetCardsRaw
import com.ichi2.anki.libanki.stats.adminSetFsrsRaw
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.withProgress

/**
 * Developer "simulation mode" for driving a demo collection into arbitrary FSRS
 * states, so the readiness dashboard can be exercised without weeks of reviews.
 *
 * This is dev tooling: it mutates the collection directly (the changes are
 * undoable in the backend). It is intentionally a plain native screen rather
 * than a polished feature. All logic lives in the Rust `admin_*` RPCs.
 */
class AdminSimulationFragment : Fragment() {
    private lateinit var fsrsSearch: EditText
    private lateinit var stability: EditText
    private lateinit var difficulty: EditText
    private lateinit var retrievability: EditText
    private lateinit var fsrsPercent: EditText

    private lateinit var advanceSearch: EditText
    private lateinit var advanceDays: EditText

    private lateinit var resetSearch: EditText
    private lateinit var resetPercent: EditText

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

        root.addView(header("Admin / simulation mode"))
        root.addView(
            note(
                "Dev tooling. Mutates the collection directly (undoable). " +
                    "Leave 'search' empty to affect the whole collection.",
            ),
        )

        // --- Set FSRS memory state ---
        root.addView(header("Set FSRS memory state"))
        fsrsSearch = field(root, "Card search (empty = all)", "", InputType.TYPE_CLASS_TEXT)
        stability = field(root, "Stability (days)", "60", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        difficulty = field(root, "Difficulty (1-10)", "5", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        retrievability =
            field(root, "Target retrievability (0-1)", "0.9", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        fsrsPercent = field(root, "Apply to random % (0 = all)", "0", InputType.TYPE_CLASS_NUMBER)
        root.addView(
            button("Apply FSRS state") {
                val request =
                    AdminSetFsrsRequest
                        .newBuilder()
                        .setSearch(fsrsSearch.text.toString())
                        .setStability(stability.floatOr(60f))
                        .setDifficulty(difficulty.floatOr(5f))
                        .setTargetRetrievability(retrievability.floatOr(0.9f))
                        .setSamplePercent(fsrsPercent.intOr(0))
                        .build()
                runAdmin("FSRS state") { adminSetFsrsRaw(request.toByteArray()) }
            },
        )

        // --- Advance days (time travel) ---
        root.addView(header("Simulate days passing (no study)"))
        advanceSearch = field(root, "Card search (empty = all)", "", InputType.TYPE_CLASS_TEXT)
        advanceDays = field(root, "Days to advance", "5", InputType.TYPE_CLASS_NUMBER)
        root.addView(
            button("Advance days") {
                val request =
                    AdminAdvanceDaysRequest
                        .newBuilder()
                        .setSearch(advanceSearch.text.toString())
                        .setDays(advanceDays.intOr(5))
                        .build()
                runAdmin("time travel") { adminAdvanceDaysRaw(request.toByteArray()) }
            },
        )

        // --- Reset to "not learned yet" ---
        root.addView(header("Reset to 'not learned yet' (new)"))
        resetSearch = field(root, "Card search (empty = all)", "", InputType.TYPE_CLASS_TEXT)
        resetPercent = field(root, "Reset random % (0 = all)", "0", InputType.TYPE_CLASS_NUMBER)
        root.addView(
            button("Reset cards to new") {
                val request =
                    AdminResetCardsRequest
                        .newBuilder()
                        .setSearch(resetSearch.text.toString())
                        .setSamplePercent(resetPercent.intOr(0))
                        .build()
                runAdmin("reset") { adminResetCardsRaw(request.toByteArray()) }
            },
        )

        return ScrollView(requireContext()).apply { addView(root) }
    }

    /** Runs an admin op off the main thread, then reports how many cards changed. */
    private fun runAdmin(
        label: String,
        block: com.ichi2.anki.libanki.Collection.() -> ByteArray,
    ) {
        launchCatchingTask {
            val updated =
                withProgress {
                    val bytes = withCol { block() }
                    AdminOpResponse.parseFrom(bytes).updated
                }
            showSnackbar("$label applied to $updated card(s)")
        }
    }

    private fun header(text: String) =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 18f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 4)
        }

    private fun note(text: String) =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            alpha = 0.8f
        }

    private fun field(
        parent: LinearLayout,
        label: String,
        default: String,
        inputType: Int,
    ): EditText {
        parent.addView(
            TextView(requireContext()).apply {
                text = label
                textSize = 13f
            },
        )
        val edit =
            EditText(requireContext()).apply {
                setText(default)
                this.inputType = inputType
                setSingleLine()
            }
        parent.addView(edit)
        return edit
    }

    private fun button(
        text: String,
        onClick: () -> Unit,
    ) = Button(requireContext()).apply {
        this.text = text
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun EditText.floatOr(fallback: Float): Float = text.toString().trim().toFloatOrNull() ?: fallback

    private fun EditText.intOr(fallback: Int): Int = text.toString().trim().toIntOrNull() ?: fallback
}

/** Builds the [Intent] that opens the admin / simulation screen. */
fun AdminSimulationDestination.toIntent(context: Context): Intent =
    SingleFragmentActivity.getIntent(context, fragmentClass = AdminSimulationFragment::class)
