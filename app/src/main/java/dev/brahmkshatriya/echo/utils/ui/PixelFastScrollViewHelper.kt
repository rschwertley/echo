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
 * sections. It does not pin or stop short: range and offset come from one window in one call, and a
 * repeated target is never re-applied (see [scrollTo]). Whether the thumb still visibly leaves the
 * finger mid-drag is the open question — see [getScrollOffset] for the one remedy, and why it is not
 * carried pre-emptively.
 */
class PixelFastScrollViewHelper(
    private val view: RecyclerView
) : FastScroller.ViewHelper {

    private companion object {
        /** No FastScroller-driven scroll outstanding. Not a valid offset. */
        const val NO_PENDING = Int.MIN_VALUE

        /** No gesture in progress, so [getScrollRange] measures live. Not a valid range. */
        const val NO_FREEZE = Int.MIN_VALUE
    }

    /** Offset [scrollTo] was last asked for, or [NO_PENDING]. See both accessors for why it exists. */
    private var pendingOffset = NO_PENDING

    /** True only inside [scrollTo]'s own scrollBy, so its onScrolled does not clear [pendingOffset]. */
    private var selfScrolling = false

    /**
     * True while FastScroller is driving the list from a touch it consumed. NOT `view.scrollState`: that
     * stays IDLE for the whole of a thumb drag, because FastScroller moves the list itself rather than
     * through RecyclerView's touch handling. Driven off whether the touch predicate consumed the event,
     * which is true exactly when FastScroller has the thumb or track — see [trackGesture].
     */
    private var gestureActive = false

    /** [liveRange] captured at the start of the current gesture, or [NO_FREEZE]. See [getScrollRange]. */
    private var frozenRange = NO_FREEZE

    private fun liveRange() =
        view.computeVerticalScrollRange() + view.paddingTop + view.paddingBottom

    /**
     * FROZEN FOR THE DURATION OF A THUMB DRAG. This is the fix for the two-images-at-different-speeds
     * effect on Home and Search.
     *
     * `computeVerticalScrollRange` is an estimate: ScrollbarHelper extrapolates from
     * `avgSizePerRow = laidOutArea / itemRange`, and `itemRange` counts ITEMS, not rows. On a mixed-span
     * grid — which is Home and Search, where configureGridLayout gives shelves the full span and grid
     * items 1 — that average swings by about spanCount between a shelf region and a 2-up region, on top of
     * the ordinary height variation. History is a LinearLayoutManager with one item per row, has no such
     * term, and has always been correct: that contrast is the evidence this is the right place to act.
     *
     * Why it matters HERE and not in the thumb position: in `mThumbOffset = thumbOffsetRange *
     * getScrollOffset() / scrollOffsetRange` the average appears in numerator and denominator and largely
     * CANCELS, so the drawn thumb was never the unstable part. It does not cancel in [scrollTo]'s delta:
     * `target = scrollOffsetRange * thumbFrac` scales with `avg * itemCount` while the subtrahend
     * `computeVerticalScrollOffset()` scales with `avg * itemsBefore`, so
     * `delta ∝ avg * (itemCount*thumbFrac - itemsBefore)` — the average MULTIPLIES the error. With a live
     * range the target moves as the content moves, so there is no fixed point to converge on and the
     * scroll oscillates frame to frame. Freezing makes the target constant for the gesture, so the same
     * iteration converges on it.
     *
     * ⚠️ NOTHING HERE BECOMES A STALE CEILING — the failure that pinned the thumb last time. That came
     * from a frozen value feeding OUR own `coerceIn(0, max)` in getScrollOffset; there is no clamp of ours
     * left. FastScroller derives `scrollOffsetRange = getScrollRange() - view.height` and uses it in two
     * places, and a wrong frozen value degrades rather than pins:
     *   - frozen TOO SMALL (grabbed over compact rows, dragged into tall shelves): the live offset outgrows
     *     the frozen denominator, so `mThumbOffset` can exceed `thumbOffsetRange` and the thumb is drawn
     *     past the track end, clipped by the RecyclerView overlay; and the finger maps to a smaller scroll
     *     span than reality, so a full-track drag lands short of the end of the list.
     *   - frozen TOO LARGE (the reverse): the thumb lags the finger and never reaches the track bottom, and
     *     a full-track drag overruns the end — harmlessly, since scrollBy clamps there.
     * Neither can pin, and neither can make the thumb vanish: `mScrollbarEnabled = scrollOffsetRange > 0`
     * is evaluated against the frozen value, and a gesture can only begin on a visible thumb, so it stays
     * true for the whole drag. That is strictly safer than the live behaviour it replaces.
     */
    override fun getScrollRange(): Int {
        if (!gestureActive) return liveRange()
        if (frozenRange == NO_FREEZE) frozenRange = liveRange()
        return frozenRange
    }

    /**
     * Plain measurement — deliberately NOT the requested offset.
     *
     * An earlier revision replayed [pendingOffset] here so the drawn thumb would exactly follow the
     * finger, on the argument that it restores what `SimpleViewHelper` gets free (`View.scrollTo` sets
     * `mScrollY` literally, so its round trip is exact). That argument still holds, but it addresses a
     * DIFFERENT symptom from the flash — the library uses this value only for
     * `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange`, i.e. where the thumb is
     * PAINTED — and it can never affect whether a scroll happens. Since much of the visible thumb jumping
     * was driven by the scroll loop that [scrollTo]'s guard now removes, it is not carried unless the
     * thumb is still seen leaving the finger on a mixed-height feed. Re-add it here if so; do not add it
     * speculatively, because it makes this method stop reporting the truth.
     */
    override fun getScrollOffset() = view.computeVerticalScrollOffset()

    /**
     * A DELTA, not an absolute seek — RecyclerView has no "set pixel scroll" to mirror `View.scrollTo`.
     *
     * ⚠️ THE IDEMPOTENCE GUARD IS LOAD-BEARING, and this is exactly why. `View.scrollTo` early-returns
     * when the position is unchanged. `RecyclerView.scrollBy` has no such guard, and worse,
     * scrollByInternal (RecyclerView.java:2266-2268) does
     * ```java
     * if (!mItemDecorations.isEmpty()) { invalidate(); }
     * ```
     * UNCONDITIONALLY, before any consumed-check — while `dispatchOnScrolled` at :2296 IS guarded by
     * `consumedX != 0 || consumedY != 0`. addOnPreDrawListener below installs an ItemDecoration, so that
     * list is never empty once the scroller exists. Therefore **every scrollBy repaints the whole
     * RecyclerView, including scrollBy(0, 0)**.
     *
     * That is the held-finger flash, and note the loop CONVERGES rather than oscillating: the finger is
     * still, so FastScroller re-delivers the same target, the delta reaches zero — and then scrollBy(0,0)
     * is called on every MOVE sample forever, each one invalidating the entire list. Not applying an
     * unchanged target is the whole fix; there is no delta small enough to be free.
     *
     * It also clamps at both ends by itself, which is the other half of why no end anchor is needed.
     * stopScroll() first, matching RecyclerViewHelper.
     *
     * ⚠️ nestedScrollBy, NOT scrollBy — THIS IS THE NESTED-PREFETCH PATH, NOT A NESTED-SCROLLING WISH.
     * `RecyclerView.scrollBy` (RecyclerView.java:2051) calls scrollByInternal DIRECTLY and is the one
     * public scroll entry point that never posts to GapWorker. All three paths that do post go through
     * it: nestedScrollByInternal (:2130), onTouchEvent's ACTION_MOVE (:3964) and ViewFlinger (:6006).
     * GapWorker is what prefetches NESTED RecyclerViews — prefetchInnerRecyclerViewWithDeadline
     * (GapWorker.java:317), collectPrefetchPositionsFromView(innerView, true) (:332), driven off
     * `holder.mNestedRecyclerView` (:357).
     *
     * So with plain scrollBy, a thumb drag scrolled the outer list while every horizontal carousel it
     * pulled into view had to create and bind its children synchronously in that layout pass, where a
     * finger scroll over the same content gets them prefetched a frame ahead. That is why the stutter
     * appeared only on Home and Search (many nested carousels), never on History or Settings (none), and
     * why it stopped the instant the finger lifted — lifting stops using this path.
     *
     * `nestedScrollBy` is the only public API that reaches nestedScrollByInternal, so it is how a
     * non-touch scroll source opts into prefetch. The cost is that it now dispatches through the whole
     * nested-scroll chain with TYPE_NON_TOUCH.
     *
     * ⚠️ THE THREE PARENTS THIS REACHES ANSWER DIFFERENTLY. Do not check one and assume the rest.
     * Verified individually against the pinned versions:
     *
     *  1. SwipeRefreshLayout (every feed screen) — REFUSES NON-TOUCH OUTRIGHT.
     *     onStartNestedScroll returns false for any `type != TYPE_TOUCH`
     *     (SwipeRefreshLayout.java:918-921, and onNestedScroll bails at :872), so the chain never even
     *     accepts. It cannot arm the refresh spinner.
     *
     *  2. BottomSheetBehavior (MediaDetailsFragment opened from the player, which sits five levels deep:
     *     BottomSheetBehavior -> LinearLayout -> MaterialCardView -> TrackInfoFragment ->
     *     MediaDetailsFragment -> SwipeRefreshLayout -> RecyclerView, with nested scrolling
     *     DELIBERATELY enabled on that recycler) — ACCEPTS NON-TOUCH, THEN CONSUMES NOTHING.
     *     Its onStartNestedScroll does NOT check type at all — it is just
     *     `lastNestedScrollDy = 0; nestedScrolled = false; return (axes & SCROLL_AXIS_VERTICAL) != 0`
     *     — so unlike (1) it does accept. Nothing comes of it:
     *       - onNestedPreScroll opens with `if (type == TYPE_NON_TOUCH) return;`, so it consumes none of
     *         the delta, never moves the sheet, and never reaches the line that sets nestedScrolled.
     *       - onNestedScroll (the 9-arg overload) has a body of exactly `return`, so lastNestedScrollDy
     *         stays 0.
     *       - onStopNestedScroll therefore hits
     *         `if (isNestedScrollingCheckEnabled() && isViewScrollingChild(target) && !nestedScrolled)
     *         return;` — the first is hardcoded true, the second is true for this recycler, and
     *         nestedScrolled is still false — and returns before any settle logic.
     *     So a thumb drag on the player's track list cannot move or dismiss the sheet, and none of the
     *     delta is eaten before the list sees it.
     *
     *     ⚠️ AND THE ONE THING THAT IS NOT INFERABLE FROM READING onStopNestedScroll ALONE: its FIRST
     *     branch is `if (child.getTop() == getExpandedOffset()) { setStateInternal(STATE_EXPANDED);
     *     return; }`, which is reached on every release, because the sheet IS at its expanded offset
     *     whenever that list is visible. That looks like it would fire the BottomSheetCallback — and
     *     this app's callback drives playerSheetState, whose observers run bgImage.resume(),
     *     emit(playerBgVisible, false) and applyPlayer(), the last of which detaches and reattaches the
     *     video surface. Firing that on every drag release would be a real regression. It does not,
     *     because setStateInternal begins `if (this.state == state) return;` and the sheet is already
     *     STATE_EXPANDED, so nothing is dispatched. If that early return ever goes away, this becomes a
     *     bug and the symptom will look nothing like the scroller.
     *
     *  3. AppBarLayout.Behavior (fragment_manage_extensions, layout_scrollFlags
     *     "scroll|enterAlwaysCollapsed|snap"; fragment_playlist_edit, AppBarLayout$ScrollingViewBehavior)
     *     — ACCEPTS NON-TOUCH AND CONSUMES IT. KNOWN BEHAVIOUR CHANGE: those two toolbars now collapse
     *     during a thumb drag, where before they sat still. Taken deliberately, because it is what a
     *     finger scroll on the same screens already does. fragment_search's CoordinatorLayout declares
     *     no AppBarLayout and no layout_behavior, so it is unaffected.
     */
    override fun scrollTo(offset: Int) {
        if (offset == pendingOffset) return
        pendingOffset = offset
        selfScrolling = true
        view.stopScroll()
        view.nestedScrollBy(0, offset - view.computeVerticalScrollOffset())
        selfScrolling = false
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
                // Anything that moves the list other than us invalidates the replay, so a finger scroll,
                // a fling or a programmatic scroll immediately puts getScrollOffset back on measurement.
                if (!selfScrolling) pendingOffset = NO_PENDING
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
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) =
                onTouchEvent.test(e).also { trackGesture(e, it) }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                trackGesture(e, onTouchEvent.test(e))
            }
        })
    }

    /**
     * Opens and closes the [getScrollRange] freeze, and clears [pendingOffset], around a
     * FastScroller-driven gesture.
     *
     * ⚠️ DRIVEN OFF WHETHER FASTSCROLLER CONSUMED THE EVENT, NOT `view.scrollState`. FastScroller moves
     * the list itself via its own scroll call, so RecyclerView's scrollState stays IDLE for the entire
     * thumb drag and would never open the freeze. FastScroller.onTouchEvent returns `mDragging`, so a
     * consumed event means exactly "it has the thumb or the track and is about to drive scrollTo".
     *
     * The range is captured LAZILY on the first [getScrollRange] read after [gestureActive] goes true,
     * not here, so it cannot go stale sitting unused. One consequence: on the track-TAP path FastScroller
     * calls scrollToThumbOffset from inside the same ACTION_MOVE being wrapped, so that first jump is
     * computed against a live range and only the drag that follows is frozen. That is the right value
     * anyway — it is the range as it stands when the gesture begins.
     *
     * The gesture end is also the only reliable point to stop replaying [pendingOffset]: FastScroller's
     * ACTION_UP path calls setDragging(false) and does not call [scrollTo], so without this the last
     * requested offset would be held indefinitely.
     */
    private fun trackGesture(event: MotionEvent, consumed: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (consumed) gestureActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                // Thawed together with the flag: leaving a capture behind would survive into the next
                // gesture as a stale starting range.
                frozenRange = NO_FREEZE
                pendingOffset = NO_PENDING
            }
        }
    }
}
