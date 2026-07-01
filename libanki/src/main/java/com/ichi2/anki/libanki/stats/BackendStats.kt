/*
 * Copyright (c) 2022 Ankitects Pty Ltd <http://apps.ankiweb.net>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.libanki.stats

import com.ichi2.anki.libanki.Collection

// These take and return bytes that the frontend TypeScript code will encode/decode.
fun Collection.cardStatsRaw(input: ByteArray): ByteArray = backend.cardStatsRaw(input)

fun Collection.graphsRaw(input: ByteArray): ByteArray = backend.graphsRaw(input)

fun Collection.getGraphPreferencesRaw(): ByteArray {
    val prefs =
        backend
            .getGraphPreferences()
            .toBuilder()
            .setBrowserLinksSupported(false)
            .build()
    return prefs.toByteArray()
}

fun Collection.setGraphPreferencesRaw(input: ByteArray): ByteArray = backend.setGraphPreferencesRaw(input)

// USMLE project: study dashboard (Memory + Coverage + calibrated Readiness).
fun Collection.studyDashboardRaw(input: ByteArray): ByteArray = backend.studyDashboardRaw(input)

// USMLE project: per-topic mastery aggregation (the "mastery query").
fun Collection.masteryByTopicRaw(input: ByteArray): ByteArray = backend.masteryByTopicRaw(input)

// USMLE project: admin / simulation mode (dev tooling; mutates the collection).
fun Collection.adminSetFsrsRaw(input: ByteArray): ByteArray = backend.adminSetFsrsRaw(input)

fun Collection.adminAdvanceDaysRaw(input: ByteArray): ByteArray = backend.adminAdvanceDaysRaw(input)

fun Collection.adminResetCardsRaw(input: ByteArray): ByteArray = backend.adminResetCardsRaw(input)
