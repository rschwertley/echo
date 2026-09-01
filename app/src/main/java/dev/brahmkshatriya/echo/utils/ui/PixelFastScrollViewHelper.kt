package dev.brahmkshatriya.echo.utils.ui

import android.graphics.Canvas
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.Predicate

/**
 * Scroll metrics for the fast scroller, taken from RecyclerView's own scrollbar estimates.
 *
 * This is deliberately the shape of AndroidFastScroll's OTHER shipped ViewHelper, `SimpleViewHelper`
 * (used for ScrollView / NestedScrollView / WebView):
 * ```java
 * getScrollRange()  = computeVerticalScrollRange();
 * getScrollOffset() = computeVerticalScrollOffset();
 * scrollTo(offset)  = scrollTo(getScrollX(), offset);
 * ```
 * Two implementations ship, and comparing them is what defines the interface's real contract, since
 * none of it is documented.
 *
 * ## The contract a ViewHelper must satisfy
 *
 * 1. `getScrollRange()`, `getScrollOffset()` and `scrollTo()`'s argument are ONE unit space.
 * 2. `getScrollRange() - view.getHeight()` must be the reachable maximum of `getScrollOffset()`.
 *    FastScroller.getScrollOffsetRange() hardcodes that subtraction, so the space has to be one in
 *    which the viewport extent IS the view height — i.e. PIXELS.
 * 3. `scrollTo(x)` then `getScrollOffset()` must return ~x. FastScroller keeps no memory of the
 *    finger: updateScrollbarState recomputes
 *    `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange` on every frame, and
 *    onPreDraw lays the thumb at `padding.top + mThumbOffset`. So the thumb tracks the finger only
 *    if the round trip is faithful.
 *
 * ## Why the previous position-based version could not work
 *
 * It expressed range and offset in adapter positions. Requirement 2 then needs "how many items fit"
 * to stand in for the view height, which is unknowable without measuring every item — that was the
 * `extent` term, and it caused every round of this: read live it moved the denominator mid-drag
 * (jumping); cached it went stale and clamped the offset (thumb pinning); the shrink guard was a
 * patch on the patch. Requirement 2 is not satisfiable in position space. Do not go back to it.
 *
 * ## Why not the library's own RecyclerViewHelper
 *
 * It estimates every item's height from `getChildAt(0)` alone:
 * ```java
 * getScrollRange()  = paddingTop + itemCount*itemHeight + paddingBottom
 * getScrollOffset() = paddingTop + firstItemPosition*itemHeight - firstItemOffset
 * scrollTo(x)       { x -= paddingTop; h = getItemHeight();
 *                     position = max(0, x/h); offsetInItem = position*h - x; ... }
 * ```
 * Working the round trip through gives `getScrollOffset() = x + position*(h_after - h_before)`, so it
 * is exact only when the scroll does not change the top child's height. On mixed-height lists it is
 * not exact, which is upstream issues #57 ("scrollbar jumps around") and #47 ("thumb is wrong
 * position in expandable RecyclerView") — the jumping on Home/Search/Settings was partly the
 * library's own defect, not something introduced here.
 *
 * RecyclerView's estimate is strictly better: ScrollbarHelper averages over the WHOLE laid-out window
 * (`avgSizePerRow = laidOutArea / itemRange`), not one child, and range and offset are computed from
 * the same window in the same call, so the average largely cancels out of the ratio.
 *
 * ## ⚠️ The `+ paddingTop + paddingBottom` is required, not cosmetic
 *
 * `computeVerticalScrollExtent` is `min(getTotalSpace(), laidOutArea)` and OrientationHelper's
 * vertical `getTotalSpace()` is `height - paddingTop - paddingBottom` — so the extent is NOT the view
 * height, it is short by the vertical padding. FastScroller subtracts the view height regardless, so
 * without adding the padding back the denominator is too small by exactly that amount and the thumb
 * runs past the bottom of the track (badly so on a barely-scrollable list with a large mini-player
 * inset, where `range - height` is small). Adding it makes the two agree exactly:
 * ```
 * getScrollRange() - view.height  ==  computeVerticalScrollRange() - computeVerticalScrollExtent()
 * ```
 * This is the same reason RecyclerViewHelper carries paddingTop/paddingBottom in its own range.
 *
 * ## Requirement 2 then holds exactly, by construction
 *
 * At maximum scroll the laid-out window is positions P..itemCount-1, and ScrollbarHelper computes
 * `avgSizePerRow = laidOutArea / itemRange` over that same window, so `avg * (itemCount - P)` IS
 * `laidOutArea` identically. Substituting into
 * `offset = itemsBefore*avg + (startAfterPadding - decoratedStart(first))` and
 * `range = avg*itemCount` reduces to `offset == range - extent`. So the end is reached by the SHAPE.
 * No end anchor, no special case, and nothing to keep in sync.
 *
 * The short-list case falls out the same way: if the content plus padding fits, `getScrollRange()` is
 * <= the view height, `getScrollOffsetRange()` is <= 0, and FastScroller's
 * `mScrollbarEnabled = scrollOffsetRange > 0` disables the thumb. No explicit gate needed.
 *
 * ## ⚠️ smoothScrollbarEnabled MUST stay on
 *
 * `LinearLayoutManager.mSmoothScrollbarEnabled` defaults to true and nothing here changes it. Turning
 * it off switches ScrollbarHelper to a completely different unit space — range becomes
 * `state.getItemCount()`, offset becomes `itemsBefore`, extent becomes a count of items — which is
 * position space again, i.e. requirement 2 breaks in exactly the way described above. It is also what
 * makes this better than the library's helper: the whole-window averaging only happens on the
 * `smoothScrollbarEnabled` branch. And MainFragment.applyInsets reads
 * `computeVerticalScrollOffset()` in pixels for the app-bar outline fade, which would break too.
 *
 * GridLayoutManager overrides computeVerticalScrollRange/Offset, but only when
 * `mUsingSpansToEstimateScrollBarDimensions` is set; it defaults false and nothing in this app sets
 * it, so both fall through to the LinearLayoutManager path together. Do not enable it without
 * revisiting the padding correction above — it changes range and offset but NOT extent, which is what
 * that correction is calibrated against.
 *
 * ## Accepted residual
 *
 * The average still varies with item heights, so thumb travel is non-linear — faster through tall
 * sections. It does not jump, pin or stop short, because range and offset come from one window in one
 * call and [scrollTo] is a self-correcting delta rather than an absolute mapping.
 */
class PixelFastScrollViewHelper(
    private val view: RecyclerView
) : FastScroller.ViewHelper {

    override fun getScrollRange() =
        view.computeVerticalScrollRange() + view.paddingTop + view.paddingBottom

    override fun getScrollOffset() = view.computeVerticalScrollOffset()

    /**
     * A DELTA, not an absolute seek — RecyclerView has no "set pixel scroll" to mirror
     * `View.scrollTo`. Each call moves by the measured error, so a shifting estimate produces a small
     * correction on the next frame instead of the divergence an absolute position mapping gave.
     * scrollBy also clamps at both ends on its own, which is the other half of why no end anchor is
     * needed. stopScroll() first, matching RecyclerViewHelper.
     */
    override fun scrollTo(offset: Int) {
        view.stopScroll()
        view.scrollBy(0, offset - view.computeVerticalScrollOffset())
    }

    // The three registrations mirror RecyclerViewHelper's own shapes (verified against the 1.3.0 AAR):
    // an ItemDecoration.onDraw for pre-draw, an OnScrollListener, and an OnItemTouchListener.
    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                onPreDraw.run()
            }
        })
    }

    override fun addOnScrollChangedListener(onScrollChanged: Runnable) {
        view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged.run()
            }
        })
    }

    // Where the fast scroller and ItemTouchHelper meet: both are OnItemTouchListeners and whichever
    // intercepts first wins. That is upstream issue #53, and the library ships
    // FixOnItemTouchListenerRecyclerView for it. This is no worse than the library's own helper, but
    // it is the line to look at if that swap ever happens.
    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) = onTouchEvent.test(e)
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                onTouchEvent.test(e)
            }
        })
    }
}
