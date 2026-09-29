package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * #130: the first-run screen must never be able to hide its own forward button.
 *
 * On 0.7.2 `activity_onboarding.xml` was a single `match_parent` LinearLayout. On a
 * Pixel 8 running Font size ~150% and Display size one step up, the column was
 * ~1080dp inside a ~760dp window: the overflow was never drawn, nothing scrolled,
 * and "Got it — connect" could not be reached at all — a hard block on first run
 * (chezgoulet/hermes-vox#130).
 *
 * These assertions hold the fix (a fillViewport ScrollView) in place structurally,
 * with no device and no Android framework: every identified view in the layout must
 * live INSIDE the scroll container, and the container must be able to stretch and
 * scroll.
 *
 * What this test deliberately does NOT claim: that the content fits any given
 * window. That needs real font metrics — see the dp-height model in the House skill
 * `hermes-vox-development` → `references/layout-overflow-forensics.md`, and the
 * device in the user's hand.
 */
class OnboardingLayoutTest {

    private val AND = "http://schemas.android.com/apk/res/android"

    /** Ids a future refactor must not move out of the scroll container. */
    private val actionable = listOf(
        "ob_got_it",    // the only forward control on the explainer step
        "connect",      // the only forward control on the connection form
        "ob_skip_how",  // both skip affordances sit with their step's button
        "skip",
        "url", "key", "model", "scope"  // the form's fields
    )

    private fun layoutFile(): File {
        val rel = "src/main/res/layout/activity_onboarding.xml"
        val candidates = listOf(
            File(rel),                       // module dir (android/app) — Gradle's test cwd
            File("app/$rel"),               // project dir (android/)
            File("android/app/$rel")        // repo root
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "activity_onboarding.xml not found from ${File(".").absolutePath}; tried " +
                    candidates.joinToString { it.path })
    }

    private fun root(): Element {
        // Namespace-aware, or `android:` attributes come back empty and every
        // assertion below would pass vacuously (or fail for the wrong reason).
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(layoutFile()).documentElement
    }

    private fun attr(el: Element, name: String): String? = el.getAttributeNS(AND, name).ifEmpty { null }

    private fun descendants(el: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            for (i in 0 until e.childNodes.length) {
                val n = e.childNodes.item(i)
                if (n is Element) { out.add(n); walk(n) }
            }
        }
        walk(el)
        return out
    }

    private fun ids(el: Element): List<String> =
        (listOf(el) + descendants(el)).mapNotNull { attr(it, "id")?.removePrefix("@+id/") }

    @Test fun root_is_a_scroll_container_that_can_stretch() {
        val r = root()
        assertEquals("the onboarding root must scroll, not clip", "ScrollView", r.tagName)
        assertEquals("fillViewport is what keeps the default layout centred", "true", attr(r, "fillViewport"))
        assertEquals("match_parent", attr(r, "layout_width"))
        assertEquals("match_parent", attr(r, "layout_height"))
        assertEquals("ob_scroll", attr(r, "id")?.removePrefix("@+id/"))
    }

    @Test fun scroll_content_is_wrap_content() {
        val r = root()
        val kids = (0 until r.childNodes.length).mapNotNull { r.childNodes.item(it) as? Element }
        assertEquals("the scroll container takes exactly one column", 1, kids.size)
        // fillViewport stretches a wrap_content child to the viewport height; a
        // match_parent child would defeat it and the container could never scroll.
        assertEquals("wrap_content", attr(kids[0], "layout_height"))
    }

    @Test fun every_identified_view_is_inside_the_scroll_container() {
        val r = root()
        // Precondition, stated here too so this test cannot pass vacuously on a
        // layout whose root does not scroll at all (which is the #130 bug).
        assertEquals("the scroll container must be the root", "ScrollView", r.tagName)
        // Note: because the container IS the root, an "outside the container" set can
        // never be non-empty — an earlier draft asserted exactly that and it was dead
        // logic. A sibling at the root is the real hazard, and "exactly one child"
        // above covers it.
        val declared = ids(r).filter { it != "ob_scroll" }.toSet()
        val missing = actionable.filterNot { it in declared }
        assertTrue("the layout no longer declares: $missing", missing.isEmpty())
    }

    @Test fun the_presence_is_still_addressable_by_the_activity() {
        // OnboardingActivity sizes the avatar by id; a rename here would crash first run.
        val r = root()
        assertTrue("ob_avatar must exist for OnboardingActivity.applyAvatarSize()",
            "ob_avatar" in ids(r))

        // applyAvatarSize() leaves the layout untouched at AVATAR_FULL_DP, so the
        // declaration and that constant must not drift apart: if they do, either the
        // default screen silently resizes or the early return never fires.
        val avatar = descendants(r).first { attr(it, "id") == "@+id/ob_avatar" }
        val want = "${OnboardingLayout.AVATAR_FULL_DP}dp"
        assertEquals("avatar width declaration", want, attr(avatar, "layout_width"))
        assertEquals("avatar height declaration", want, attr(avatar, "layout_height"))
    }
}
