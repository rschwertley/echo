package dev.brahmkshatriya.echo.utils.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.Predicate

/**
 * Scroll metrics for the fast scroller, measured in ADAPTER POSITIONS rather than extrapolated pixels.
 *
 * ## Why this exists
 *
 * AndroidFastScroll's own RecyclerViewHelper computes:
 * ```
 * getItemHeight()   = decorated height of getChildAt(0)          // ONE child, the current top one
 * getScrollRange()  = itemCount * getItemHeight() + paddings
 * getScrollOffset() = firstItemPosition * getItemHeight() - firstItemOffset + paddingTop
 * ```
 * It samples the height of whichever item is currently at the top and assumes every item in the list is
 * that tall. On uniform rows that is exact — which is why History and playlist track lists were always
 * fine. On mixed content (Home, Search, Settings: cards, shelves, category headers, preference
 * categories) it rescales the entire range every time a different-height item becomes the top child:
 *  - short item on top  -> range shrinks  -> offset/range spikes -> thumb RACES to the bottom
 *  - tall item on top   -> range balloons -> ratio collapses     -> thumb SNAPS back to the top
 *  - short enough, and `itemCount * smallHeight + padding` drops BELOW the viewport height, so
 *    FastScroller's `mScrollbarEnabled = getScrollOffsetRange() > 0` goes false and the thumb VANISHES
 *    until a taller item reaches the top. (That was Search's missing middle.)
 *
 * NOTE it never consults computeVerticalScrollRange/Offset, so LinearLayoutManager's
 * setSmoothScrollbarEnabled(false) would not have fixed any of this — and would have broken the app-bar
 * outline fade in MainFragment.applyInsets, which reads computeVerticalScrollOffset in pixels.
 *
 * ## Why positions and not rows
 *
 * The obvious fix is to count visual ROWS via `spanSizeLookup.getSpanGroupIndex(position, spanCount)`,
 * which would also fix the library's other bug (it derives rows as `position / spanCount`, valid only
 * when every item spans one column — `configureGridLayout` installs a variable lookup and HeaderAdapter
 * returns the full span). That was rejected, and must stay rejected:
 *
 *  1. **It throws.** getSpanGroupIndex bounds-checks nothing; it calls getSpanSize(i) for every i from
 *     its cache reference index up to the target, and ours is GridAdapter.Concat.getSpanSize ->
 *     ConcatAdapter.getWrappedAdapterAndPosition, which throws IllegalArgumentException on an
 *     out-of-range position. GridAdapter's existing guarded walk survives only because it runs inside
 *     getItemOffsets where `state.itemCount` is layout-consistent. A ViewHelper has NO RecyclerView.State
 *     — it runs on scroll and pre-draw callbacks, where the only count available is the live adapter
 *     count, which is exactly the stale-position window.
 *  2. **It is O(position) per frame after any list update.** GridLayoutManager invalidates both span
 *     caches on every onItemsAdded/Removed/Moved/Updated/Changed. After an invalidation the walk starts
 *     at 0, so deep in a paged feed it is thousands of ConcatAdapter binary searches per scroll frame,
 *     re-triggered on every page append — a worse performance bug than the one being fixed, biting
 *     hardest exactly where the feeds page.
 *  3. **It reads span state that changes at runtime.** configureGridLayout defers spanCount and a fresh
 *     spanSizeLookup into a nested post {}; between that block and the layout its requestLayout()
 *     triggers, visible positions are from the OLD layout at the OLD span count while the lookup and
 *     count are new.
 *
 * This helper therefore reads NONE of it: no spanCount, no spanSizeLookup, no getSpanGroupIndex, no
 * getSpanSize, no computeVerticalScrollRange/Offset. Only itemCount, the first child, its decorated
 * bounds, and padding. Every hazard above is structurally absent rather than caught — there is no throw
 * site, no cache, and no span state on this path.
 *
 * KNOWN AND ACCEPTED COST: with variable spans a full-width header occupies a whole row but counts as one
 * position, so it is slightly under-weighted in the thumb's travel. That is a small CONSTANT
 * proportionality error — a smoothly slightly-off thumb — not the jumping this replaces, and it is stable
 * as you scroll. Do not add row awareness back to chase it without evidence it is visible on device.
 *
 * ## Read-only on the scroll path
 *
 * Nothing here mutates adapter or layout structure, which is what keeps it clear of the
 * `IndexOutOfBoundsException: Inconsistency detected` class that the deferred post in configureGridLayout
 * exists to avoid. The single write is [scrollTo] -> scrollToPositionWithOffset, which is user-initiated,
 * is what the library's own helper does, and changes no item count. KEEP IT THAT WAY: anything added to
 * the getters must be a read.
 */
class PositionFastScrollViewHelper(
    private val view: RecyclerView
) : FastScroller.ViewHelper {

    private val tempRect = Rect()

    /**
     * Sub-position resolution. Range and offset are both in units of UNIT-per-item, so the ratio the
     * FastScroller actually uses is independent of its value — it only sets how finely the thumb can
     * interpolate WITHIN one item. Large enough that `itemCount * UNIT` clears any viewport height for a
     * genuinely scrollable list, small enough that it cannot overflow Int at any plausible item count
     * (Int.MAX / 1000 is ~2.1M items).
     */
    private companion object {
        const val UNIT = 1000
    }

    private fun firstChild() = if (view.childCount == 0) null else view.getChildAt(0)

    private fun itemCount() = view.layoutManager?.itemCount ?: 0

    /**
     * ⚠️ THE `+ view.height` IS LOAD-BEARING — DO NOT SIMPLIFY THIS TO `itemCount * UNIT`.
     *
     * FastScroller does not use this value directly. It computes
     * `getScrollOffsetRange() = getScrollRange() - view.height` and then positions the thumb as
     * `thumbOffsetRange * getScrollOffset() / scrollOffsetRange`. That subtraction is in PIXELS, so a
     * plain `itemCount * UNIT` would be subtracting pixels from position-units — dimensionally
     * incoherent, and the visible symptom is that the thumb never reaches the bottom of the track (it
     * stalls at roughly `(itemCount - visibleCount) / itemCount` of the way down, so ~94% on a 50-item
     * list).
     *
     * Instead the offset RANGE is expressed in position-units and `view.height` is added back so that
     * FastScroller's own subtraction recovers it exactly:
     * ```
     * offsetRange = (itemCount - childCount) * UNIT + height - height = (itemCount - childCount) * UNIT
     * ```
     * `itemCount - childCount` is the largest first-visible position, which is exactly what
     * [getScrollOffset] maxes out at — so the thumb reaches both ends of the track.
     *
     * childCount is the estimator for "how many items fit", and it moves by ±1 as rows scroll in and out
     * (more on mixed heights). That is a small LINEAR wobble in the denominator — about 1% on a 100-item
     * uniform list — not the multiplicative rescale that made the old thumb jump, and it reflects a real
     * change in how many items fit rather than an error. Do not chase it with a height-derived estimate;
     * that is what this class exists to get away from.
     */
    override fun getScrollRange(): Int {
        val itemCount = itemCount()
        if (itemCount == 0) return 0
        val childCount = view.childCount
        // Short-list gate. If every item is laid out there is nothing to scroll, so report 0 and let
        // FastScroller's `mScrollbarEnabled = getScrollOffsetRange() > 0` disable the thumb outright.
        // O(1) and exact, and — unlike the library's `itemCount * child0Height vs viewHeight` test — it
        // cannot flicker with item heights, so a short list shows NO thumb rather than one that appears
        // and vanishes.
        if (childCount >= itemCount) return 0
        return (itemCount - childCount) * UNIT + view.height
    }

    override fun getScrollOffset(): Int {
        val itemCount = itemCount()
        if (itemCount == 0) return 0
        val child = firstChild() ?: return 0
        // NO_POSITION for a child pending removal — the natural guard for the stale-position window.
        val position = view.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return 0
        // Coerced, not trusted: the position comes from the LAST layout while itemCount is the LIVE
        // adapter count, so after a submitList that removed items it can be past the end. Degrading to a
        // wrong thumb position for one frame — which the next layout corrects — is the required
        // behaviour; throwing is not.
        val clamped = position.coerceIn(0, itemCount - 1)
        view.getDecoratedBoundsWithMargins(child, tempRect)
        val height = tempRect.height()
        // Interpolate inside the current item using THAT child's own height and top. This is the one
        // measurement the library got right: it is correct precisely because it describes the specific
        // child being measured, rather than being extrapolated across items that may be nothing like it.
        val within = if (height <= 0) 0
        else (view.paddingTop - tempRect.top).coerceIn(0, height) * UNIT / height
        // Clamped to the OFFSET range (what FastScroller divides by), not to getScrollRange() — those
        // differ by view.height, and overshooting the offset range draws the thumb past the end of the
        // track. At the very bottom `clamped` already equals the max first-visible position, so `within`
        // would push it over without this.
        val max = (getScrollRange() - view.height).coerceAtLeast(0)
        return (clamped * UNIT + within).coerceIn(0, max)
    }

    override fun scrollTo(offset: Int) {
        val itemCount = itemCount()
        if (itemCount == 0) return
        val layoutManager = view.layoutManager as? LinearLayoutManager ?: return
        view.stopScroll()
        val position = (offset / UNIT).coerceIn(0, itemCount - 1)
        val within = offset - position * UNIT
        // Convert the sub-item fraction back to pixels with the current first child's height. It is only
        // an estimate for the TARGET item, but it is bounded by one item's height and self-corrects on
        // the next frame, so it cannot accumulate — the position itself is exact.
        val child = firstChild()
        val height = if (child == null) 0 else {
            view.getDecoratedBoundsWithMargins(child, tempRect)
            tempRect.height()
        }
        val pixels = if (height <= 0) 0 else within * height / UNIT
        layoutManager.scrollToPositionWithOffset(position, -pixels)
    }

    // The three registration methods mirror RecyclerViewHelper's own shapes (verified against the 1.3.0
    // AAR): an ItemDecoration.onDraw for pre-draw, an OnScrollListener, and an OnItemTouchListener.
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

    // This is where the fast scroller and ItemTouchHelper meet: both are OnItemTouchListeners, and
    // whichever intercepts first wins. That is upstream issue #53 ("when scrolling, item dragging will
    // also be triggered"), and the library ships FixOnItemTouchListenerRecyclerView for it. Registering
    // here is no worse than the library's own helper — but this is the line to look at when the swap
    // happens.
    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        view.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) = onTouchEvent.test(e)
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                onTouchEvent.test(e)
            }
        })
    }
}
