// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.pages

import android.content.Context
import android.content.Intent
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.destinations.ReadinessDestination

/**
 * USMLE study-readiness dashboard.
 *
 * Hosts the shared SvelteKit `readiness` page (Memory + Coverage + calibrated
 * Readiness), served from the backend web assets exactly like [Statistics].
 * All computation happens in the Rust backend via the `studyDashboard` RPC.
 */
class ReadinessPage : PageFragment() {
    override val pagePath: String = "readiness"
}

/** Builds the [Intent] that opens the readiness screen. */
fun ReadinessDestination.toIntent(context: Context): Intent =
    SingleFragmentActivity.getIntent(context, fragmentClass = ReadinessPage::class)
