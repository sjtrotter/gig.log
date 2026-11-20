/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Census wire contract (ADR-0011, #1173). This Apache-2.0 included build depends
 * on nothing but the JDK and kotlinx-serialization.
 */
package cloud.trotter.census.contract

import cloud.trotter.census.contract.contributor.ContributorSlots

/**
 * The identity of a `uinode.clicked.v1` item (#1634, ADR-0011 §19): `day|fingerprint|layout|ownerPath`, where layout
 * is [ContributorSlots.layout], byte for byte the server's `SlotWalk` layout. Two owners on one screen, or one owner on
 * two layouts that share a fingerprint, are two identities. The phone selects and batches by it and the server counts
 * duplicates by it, so both read it from here. No part can hold a `|` (a date, two hex digests and a [NodePath]), so
 * the joined string names one tuple.
 */
object ClickedIdentity {
    private const val SEPARATOR = "|"

    /** The identity of [item]; null when it is not a clicked item or the layout digest fails. */
    fun of(item: UiSkeletonDto): String? {
        val tap = item.tap ?: return null
        val layout = ContributorSlots.layout(item) ?: return null
        return listOf(item.day, item.fingerprint, layout, tap.ownerPath).joinToString(SEPARATOR)
    }

    /** The phone's selection key for [item]: the lowercase hex sha256 of its identity; null when [of] is. */
    fun selectionKey(item: UiSkeletonDto): String? = of(item)?.let { sha256OrNull(it) }
}
