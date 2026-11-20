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

import kotlinx.serialization.Serializable

/**
 * A node of a skeleton's tree named by position: `root`, then each step down the child index over the tree as sent
 * (anonymous wrappers included), the same walk as the contributor slot ids (`root/0/2`), with no text key after it.
 * Canonical: at most [MAX_STEPS] steps, each `0` or `1`..`9999` with no leading zero, so one node has one path.
 */
object NodePath {
    const val ROOT: String = "root"
    const val MAX_STEPS: Int = 64

    /** `root` plus [MAX_STEPS] steps of `/` and four digits; a longer string is refused before the regex runs. */
    private const val MAX_LENGTH: Int = 4 + MAX_STEPS * 5
    private val GRAMMAR = Regex("^root(/(0|[1-9][0-9]{0,3})){0,64}$")

    /** The child indices [path] steps through, root first; null when [path] is not canonical. */
    fun parse(path: String): List<Int>? {
        if (path.length > MAX_LENGTH || !GRAMMAR.matches(path)) return null
        return path.split('/').drop(1).map { it.toInt() }
    }

    /** The canonical path of [steps]; null when a step or the depth is out of range. */
    fun of(steps: List<Int>): String? {
        if (steps.size > MAX_STEPS || steps.any { it !in 0..9_999 }) return null
        return ROOT + steps.joinToString("") { "/$it" }
    }
}

/**
 * The `tap` of a `uinode.clicked.v1` item (#1634, census#87): which node of the item's own `root` the dasher tapped.
 * [ownerPath] is the node that takes the click (`isClickable || hasClickAction`), the one `UiInteractionHandler` would
 * bind; [tapPath] is the tapped node itself, present only when it is not the owner and survived the filter; and
 * [generation] is the rules generation the click was classified with, as its 16-hex wire id. The owner's class, id,
 * flags and words are read from the body at [ownerPath], never repeated here.
 *
 * Construction checks the grammars; [holdsIn] checks the paths against the tree, which only [UiSkeletonDto] holds.
 */
@Serializable
data class TapRef(
    val ownerPath: String,
    val tapPath: String? = null,
    val generation: String,
) {
    init {
        require(NodePath.parse(ownerPath) != null) { "an owner path is a canonical node path" }
        require(tapPath == null || NodePath.parse(tapPath) != null) { "a tap path is a canonical node path" }
        require(WireTokenGrammars.shortDigest.matches(generation)) { "a generation is 16 lowercase hex" }
    }

    /**
     * True when this reference holds in [root]: [ownerPath] names a node that takes clicks and, when [tapPath] is
     * present, it names a node strictly below the owner (a step-wise prefix, never a string prefix: `root/1` is not an
     * ancestor of `root/10`), and neither the tapped node nor any node between the two takes clicks, so the owner is
     * the tapped node's nearest one.
     */
    fun holdsIn(root: UiSkeletonNodeDto): Boolean {
        val owner = NodePath.parse(ownerPath) ?: return false
        val ownerNode = chain(root, owner)?.last() ?: return false
        if (!ownerNode.takesClick()) return false
        val tapText = tapPath ?: return true
        val tap = NodePath.parse(tapText) ?: return false
        if (tap.size <= owner.size || tap.subList(0, owner.size) != owner) return false
        val tapChain = chain(root, tap) ?: return false
        // tapChain[d] is the node at depth d; the owner sits at owner.size, so this is every node below it down to the tap.
        return tapChain.subList(owner.size + 1, tapChain.size).none { it.takesClick() }
    }
}

/** The nodes from [root] down [steps], root first; null when a step leaves the tree. */
private fun chain(root: UiSkeletonNodeDto, steps: List<Int>): List<UiSkeletonNodeDto>? {
    val out = ArrayList<UiSkeletonNodeDto>(steps.size + 1)
    var node = root
    out += node
    for (step in steps) {
        node = node.children.getOrNull(step) ?: return null
        out += node
    }
    return out
}

/** The owner rule `ActionOwnerWalk` binds with: the node is clickable or advertises the click action. */
private fun UiSkeletonNodeDto.takesClick(): Boolean = isClickable || hasClickAction
