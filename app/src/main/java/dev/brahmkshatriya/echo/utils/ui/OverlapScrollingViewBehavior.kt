package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.appbar.AppBarLayout

/**
 * A [AppBarLayout.ScrollingViewBehavior] that lets the scrolling child span the FULL viewport and sit
 * UNDER the AppBarLayout, instead of being sized to `viewport - headerHeight` and positioned below it.
 *
 * ⚠️ WHY THIS CLASS EXISTS AT ALL: the branch it selects is already implemented in Material — it is just
 * unreachable. Decoded from the material 1.14.0 AAR with `javap -p -c` (no sources jar is published to the
 * Gradle cache), `HeaderScrollingViewBehavior.onMeasureChild` reads:
 *
 *     height = availableHeight + getScrollRange(header)
 *     headerHeight = header.getMeasuredHeight()
 *     if (shouldHeaderOverlapScrollingChild()) {
 *         child.setTranslationY(-headerHeight)      // full height, drawn from the header's top
 *     } else {
 *         child.setTranslationY(0)
 *         height = height - headerHeight            // shortened, laid out below the header
 *     }
 *
 * and `HeaderScrollingViewBehavior.shouldHeaderOverlapScrollingChild()` decodes to `iconst_0; ireturn` —
 * a hardcoded `false`. `AppBarLayout$ScrollingViewBehavior` does not override it; its `onMeasureChild` is
 * a pure `invokespecial` pass-through to the base. There is no XML attribute and no setter, so a subclass
 * is the only way in.
 *
 * ⚠️ `app:behavior_overlapTop` IS NOT THIS, AND DOES NOT WORK HERE. Decoded from the same jar,
 * `getOverlapPixelsForOffset` returns `overlayTop == 0 ? 0 : clamp((int)(getOverlapRatioForOffset(header)
 * * overlayTop), 0, overlayTop)`, and that value only OFFSETS the child upward — the height was already
 * reduced by headerHeight in the non-overlap branch. Setting it would pull the list up under the toolbar
 * and leave a hole of the same size at the bottom of the screen.
 *
 * WHY THE SCROLL-ARITHMETIC OBJECTION DOESN'T APPLY ANY MORE, since this route was scoped and REJECTED
 * once and would otherwise read as an unexplained reversal. The rejection was correct then: with a
 * CollapsingToolbarLayout carrying scroll flags, overlap mode grows the RecyclerView by the header height
 * AND the header's travel is already in `appBarRange`, so PixelFastScrollViewHelper's composite counted
 * the same pixels twice. That premise is gone. fragment_media.xml now holds a NON-SCROLLING AppBarLayout:
 * with no `scroll` flag on any child, `AppBarLayout.getTotalScrollRange()` breaks its loop at i=0 and
 * returns `Math.max(0, 0) = 0`, so
 *   - `appBarRange` is 0 and `appBarConsumed` never moves (the offset listener fires only when the
 *     AppBar's own offset changes, and it has no range to change through), and
 *   - `getScrollRange(header)` contributes 0 to the measure above, so `height` is exactly the viewport.
 * Nothing is double-counted because there is no header travel to count at all. The composite reduces to
 * the flat-screen path, which is what it already does on this screen today.
 *
 * ⚠️ MEASURE ONLY — NOT THE NESTED-SCROLL PATH. `shouldHeaderOverlapScrollingChild` has exactly ONE call
 * site in the whole class (offset 117 of `onMeasureChild`, verified by decoding every reference in
 * HeaderScrollingViewBehavior). It is never consulted during layout, offsetting, or nested scroll. The
 * three-parent asymmetry recorded at PixelFastScrollViewHelper.scrollTo — SwipeRefreshLayout refusing
 * non-touch scroll, BottomSheetBehavior accepting and consuming nothing, AppBarLayout accepting and
 * consuming — is untouched by this class.
 *
 * SUBCLASS, NOT DELEGATION, matching the DefaultLoadControl decision in this project: the behaviour that
 * matters lives in the Java class's own overrides, and `by` would forward the public surface while losing
 * every protected hook the base calls on itself.
 *
 * ⚠️ INSTANTIATED BY REFLECTION FROM THE `app:layout_behavior` STRING IN fragment_media.xml. Nothing
 * references it from code, so R8 will rename or remove it without a keep rule and CoordinatorLayout will
 * throw at inflation — visible ONLY in a minified build. See the rule and its `# anchor:` line in
 * app/proguard-rules.pro, and the matching entry in `critical` in app/build.gradle.kts.
 * The two-arg (Context, AttributeSet) constructor is REQUIRED: that is the one CoordinatorLayout looks up.
 */
@Suppress("unused") // referenced only from fragment_media.xml, by class name
class OverlapScrollingViewBehavior(
    context: Context, attrs: AttributeSet?
) : AppBarLayout.ScrollingViewBehavior(context, attrs) {

    // If this ever fails to compile ("cannot access shouldHeaderOverlapScrollingChild"), the fallback is
    // to override the PUBLIC onMeasureChild instead and reproduce the four lines above directly — measure
    // the child at the full viewport height and setTranslationY(-header.measuredHeight). The method is
    // `protected` in the bytecode (javap: `protected boolean shouldHeaderOverlapScrollingChild();`) and is
    // inherited through the PUBLIC ScrollingViewBehavior, so overriding it from this package is legal even
    // though HeaderScrollingViewBehavior itself is package-private — a subclass may access a protected
    // member of its superclass regardless of that superclass's own visibility.
    override fun shouldHeaderOverlapScrollingChild() = true
}
