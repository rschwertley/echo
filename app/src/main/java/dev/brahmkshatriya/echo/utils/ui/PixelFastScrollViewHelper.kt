package dev.brahmkshatriya.echo.utils.ui

import android.graphics.Canvas
import android.util.Log
import dev.brahmkshatriya.echo.utils.CrashKeys
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
        /** Smoothing factor for [perItemSpan]. SYMMETRIC — see [sampleSpan] for why it stopped being biased. */
        const val SPAN_ALPHA = 0.05
    }

    /**
     * True while FastScroller is driving the list from a touch it consumed. NOT `view.scrollState`: that
     * stays IDLE for the whole of a thumb drag, because FastScroller moves the list itself rather than
     * through RecyclerView's touch handling. Driven off whether the touch predicate consumed the event,
     * which is true exactly when FastScroller has the thumb or track — see [trackGesture].
     */
    private var gestureActive = false

    /**
     * The list's scrollable span in estimate-pixels, captured ONCE at the start of each gesture. This is
     * the single place the estimate enters a drag; everything after is finger deltas. Read [scrollTo].
     */
    private var gestureSpan = 1

    /** Previous thumb fraction in this gesture, or NaN before the first. */
    private var lastFraction = Double.NaN

    /** Sub-pixel remainder carried between frames so a long slow drag cannot drift. */
    private var pendingPixels = 0.0

    /**
     * Running mean of `computeVerticalScrollRange() / itemCount` — the estimated height of ONE item,
     * averaged over everywhere the user has scrolled. 0.0 until the first sample. Read by
     * [estimatedRange], sampled by [sampleSpan].
     *
     * ⚠️ PER ITEM, NOT THE TOTAL RANGE, AND THAT IS WHAT MAKES IT SELF-CORRECTING ACROSS CONTENT CHANGES.
     * A running mean of the total range would describe the list it was measured on, so a feed refresh or a
     * Search tab switch would leave it describing a list that no longer exists, and it would need
     * invalidating on adapter changes. Storing the per-item mean and multiplying by the LIVE itemCount at
     * read time separates the two: item count is exact and always current, and the per-item mean stays
     * representative as long as the new content is made of similar rows — which it is, since the same
     * adapters build it. No reset needed, and no AdapterDataObserver to register or leak.
     *
     * WHY A MEAN AT ALL: ScrollbarHelper extrapolates from `avgSizePerRow = laidOutArea / itemRange`, and
     * itemRange counts ITEMS, not rows. On Home and Search — full-span shelves alongside 2-up grid items —
     * that per-item figure is roughly the span count larger in a shelf region than in a grid region, so a
     * single live sample depends on WHERE the user grabbed the thumb, and the drag came out proportionally
     * fast or slow by about 2x. On History, Library and Settings the LinearLayoutManager puts one item per
     * row with near-uniform heights, the figure is the same everywhere, and a single sample was already
     * correct — which is exactly why those three screens have always behaved and these two have not. The
     * estimate stops depending on the grab point. It is a PLAIN mean, deliberately — an earlier revision
     * biased it toward the largest observed figure and overshot; see [sampleSpan].
     *
     * WHICH SECTIONS ARE WHICH is FeedAdapter.getSpanSize: Header and HorizontalList always take the full
     * span, and on a phone (sw < 600dp, `phoneSingleColumn`) Category, Media and Video do too. Only
     * MediaGrid and VideoHorizontal take span 1, and CategoryGrid takes count/2 — those are the tile rows
     * whose per-item figure is halved. NOTHING NEEDS ENUMERATING HERE, though: a section is sampled by
     * whatever it actually measures, so a shelf type nobody anticipated is accounted for by construction
     * rather than by this list being kept current.
     */
    private var perItemSpan = 0.0

    private fun liveRange() =
        view.computeVerticalScrollRange() + view.paddingTop + view.paddingBottom

    /**
     * Folds one observation into [perItemSpan]. Called from the scroll listener, so it samples wherever
     * the user actually goes rather than wherever they happen to stop.
     *
     * ⚠️ SYMMETRIC. IT WAS ASYMMETRIC (rise 0.2 / fall 0.02) AND THAT WAS A REASONING ERROR — do not
     * reinstate the bias without re-reading this.
     *
     * On-device testing (2026-09-02) found that grabbing the thumb with carousels filling the screen
     * reached the end of the list exactly, while grabbing with tiles filling the screen landed short.
     * At that time gestureSpan was a LIVE sample of whatever region was under the finger, so the correct
     * reading of that result is narrow: A CAROUSEL REGION'S per-item figure is approximately the true
     * item-weighted mean. It was instead generalised to "the LARGEST per-item figure ever observed is the
     * true mean", and the estimate biased toward that maximum. Those are different quantities and the
     * second is strictly larger, so every grab then got a span above the truth — each finger pixel bought
     * too much scroll and the page reached the end before the finger reached the bottom of the track.
     * The 2026-09-03 build showed exactly that.
     *
     * The target is the true ITEM-WEIGHTED mean, `totalContentHeight / itemCount`, and nothing about the
     * evidence says it sits at the top of the observed range — only that it is near a carousel region's
     * figure, which tile regions pull down from.
     *
     * KNOWN RESIDUAL, so it is not mistaken for a new fault: samples arrive from onScrolled, roughly one
     * per frame of scrolling, so they are PIXEL-weighted rather than ITEM-weighted. A region contributes
     * samples in proportion to its pixel height, and tall-per-item regions occupy more pixels per item, so
     * even a symmetric mean sits slightly ABOVE the item-weighted truth — mathematically the ratio of the
     * second moment to the first rather than the first to the count. Expect a small residual overshoot.
     * If that turns out to matter, the principled fix is to weight by ITEMS traversed instead: accumulate
     * `pixels += |dy|` and `items += |dy| / sample`, and take `pixels / items`, which is literally
     * height-over-count across everywhere the user has been. Do that only with evidence; it is more
     * machinery than a single alpha.
     *
     * A fling delivers onScrolled roughly per frame, so a single flick is ~60 samples — enough to
     * converge well within one gesture.
     */
    private fun sampleSpan() {
        val itemCount = view.adapter?.itemCount ?: return
        if (itemCount <= 0) return
        val range = view.computeVerticalScrollRange()
        if (range <= 0) return
        val sample = range.toDouble() / itemCount
        perItemSpan =
            if (perItemSpan == 0.0) sample else perItemSpan + SPAN_ALPHA * (sample - perItemSpan)
    }

    /**
     * The list's total scrollable height, from the running per-item mean scaled by the LIVE item count.
     *
     * Falls back to [liveRange] when no sample has been taken yet, which is the pre-convergence case and
     * is exactly the behaviour this replaces — never worse than a single live read, only better once the
     * mean exists. In practice a sample almost always exists before it can be read: the thumb auto-hides
     * and is shown by FastScroller's onScrollChanged, so the list has to have scrolled at least once
     * before the thumb can be grabbed, and that same scroll feeds [sampleSpan].
     */
    private fun estimatedRange(): Int {
        val itemCount = view.adapter?.itemCount ?: 0
        if (perItemSpan == 0.0 || itemCount <= 0) return liveRange()
        return (perItemSpan * itemCount).toInt() + view.paddingTop + view.paddingBottom
    }

    /**
     * LIVE, deliberately — an earlier revision froze this for the duration of a gesture and it was the
     * wrong lever. Live, the estimate's average appears in both numerator and denominator of
     * `mThumbOffset = thumbOffsetRange * getScrollOffset() / scrollOffsetRange` and largely CANCELS,
     * leaving roughly `itemsBefore / itemCount` — so the drawn thumb is stable. Freezing the denominator
     * while the numerator stayed live broke that cancellation and made the thumb wander mid-drag, which
     * is exactly what was observed. [scrollTo] no longer needs it frozen; see there.
     */
    override fun getScrollRange() = liveRange()

    /**
     * Plain live measurement, and with [scrollTo] no longer subtracting it there is nothing it can feed
     * back into. It is read exactly once per gesture now, on the first (absolute) call.
     *
     * An earlier revision replayed the requested offset here so the drawn thumb would exactly follow the
     * finger. Not needed: with [getScrollRange] live, the estimate's average cancels between this and the
     * range, so the thumb already sits at roughly `itemsBefore / itemCount` on its own.
     */
    override fun getScrollOffset() = view.computeVerticalScrollOffset()

    /**
     * A DELTA, not an absolute seek — RecyclerView has no "set pixel scroll" to mirror `View.scrollTo`.
     *
     * ⚠️ THIS APPLIES A DELTA RECOVERED FROM THE FINGER, NOT THE ABSOLUTE TARGET IT IS HANDED. That is
     * the whole point, and it is what makes a thumb drag behave like a finger scroll.
     *
     * A FINGER scroll is perfect on these same screens with the same unstable estimate, because
     * RecyclerView.onTouchEvent computes `dy` from finger movement and hands it to scrollByInternal —
     * there is not a single compute*Scroll* call anywhere in that method. The estimate is never in the
     * loop. A THUMB drag was different only because of what we did with FastScroller's argument:
     * `scrollToThumbOffset` builds `scrollOffset = getScrollOffsetRange() * thumbOffset /
     * thumbOffsetRange` where `thumbOffset = mDragStartThumbOffset + (eventY - mDragStartY)` is pure
     * finger pixels and `thumbOffsetRange` is pure geometry — so the ONLY estimate dependence is
     * getScrollOffsetRange(), i.e. ours. Subtracting `computeVerticalScrollOffset()` from that target put
     * the estimate back in the loop on both sides: the target scaled with `avg * itemCount`, the
     * subtrahend with `avg * itemsBefore`, so `delta ∝ avg * (itemCount*thumbFrac - itemsBefore)` and the
     * average MULTIPLIED the error. Content lurched, the window changed, the average moved, repeat — two
     * images at different offsets, alternating frames.
     *
     * So invert FastScroller's own multiplication with the SAME span it just used to recover the finger
     * fraction, and convert fraction deltas to pixels with a span read ONCE per gesture:
     *   fraction = offset / (getScrollRange() - view.height)      // undoes the library's multiply
     *   pixels   = (fraction - lastFraction) * gestureSpan        // gestureSpan captured at gesture start
     * `computeVerticalScrollOffset()` is never consulted after the first call, so there is no feedback
     * path at all. An error in gestureSpan makes the whole drag proportionally fast or slow — a bounded,
     * monotonic wrongness — instead of oscillating. That is the same trade a finger scroll makes.
     *
     * The first call of a gesture still lands ABSOLUTELY, which is required rather than incidental: the
     * track-TAP path sets mDragStartThumbOffset from the tap position and jumps, so the first target is
     * genuinely somewhere else. On a thumb GRAB the first target is where the content already is, so the
     * same absolute step is ~0.
     *
     * Sub-pixel remainders accumulate in [pendingPixels] rather than being rounded away each frame, so a
     * long slow drag cannot drift.
     *
     * A zero delta returns WITHOUT calling scrollBy, and that is load-bearing, not an optimisation.
     * RecyclerView.scrollBy has no no-op guard, and scrollByInternal (RecyclerView.java:2266-2268) runs
     * `if (!mItemDecorations.isEmpty()) { invalidate(); }` UNCONDITIONALLY before any consumed-check —
     * while dispatchOnScrolled at :2296 IS guarded. addOnPreDrawListener below installs an
     * ItemDecoration, so that list is never empty and every scrollBy repaints the whole list, including
     * scrollBy(0, 0). A held finger produces an unchanged fraction, so this returns and the screen stops
     * flashing.
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
        view.stopScroll()
        // The span FastScroller itself just multiplied by, so dividing recovers its thumbOffset fraction.
        // Two DIFFERENT spans, deliberately. `liveSpan` must be the value FastScroller itself just
        // multiplied by, or dividing would not recover its thumb fraction — so it stays live. `gestureSpan`
        // converts that fraction into content pixels and is the one place the estimate enters the drag, so
        // it comes from the running mean instead of a single window-dependent sample.
        val liveSpan = (liveRange() - view.height).coerceAtLeast(1)
        val fraction = offset.toDouble() / liveSpan
        if (!gestureActive || lastFraction.isNaN()) {
            gestureSpan = (estimatedRange() - view.height).coerceAtLeast(1)
            // ⚠️ TRACE — REMOVE WITH THE INVESTIGATION.
            // Prints BOTH SIDES OF THE ONE COMPARISON THAT MATTERS: est is EMA-derived
            // (perItemSpan * itemCount + padding), live is measured now (liveRange()). Both are
            // padding-inclusive so they are directly comparable, and est/live is the calibration error —
            // 1.00 means the EMA agrees with the layout.
            // An earlier version printed the RAW computeVerticalScrollRange() beside gestureSpan, which
            // looked subtractable and was not: gestureSpan comes from est, not from the raw call, and the
            // raw call omits padding. perItem is printed with a decimal because at 150+ items one unit of
            // it is 150+ px of range, and truncating made the arithmetic uncheckable.
            // WHAT TO READ: a stable est/live is fine at any value (Home sits at 1.20 and behaves); an
            // est/live that MOVES between grabs on an unchanging screen is the fault.
            Log.d(
                "GladixScroll",
                "gesture start: perItem=%.2f items=%d est=%d live=%d est/live=%.2f gestureSpan=%d viewH=%d feedLoads=%d"
                    .format(
                        perItemSpan, view.adapter?.itemCount ?: 0, estimatedRange(), liveRange(),
                        if (liveRange() > 0) estimatedRange().toDouble() / liveRange() else 0.0,
                        gestureSpan, view.height, CrashKeys.feedLoadCount()
                    )
            )
            lastFraction = fraction
            pendingPixels = 0.0
            val jump = offset - view.computeVerticalScrollOffset()
            if (jump != 0) view.nestedScrollBy(0, jump)
            return
        }
        pendingPixels += (fraction - lastFraction) * gestureSpan
        lastFraction = fraction
        val delta = pendingPixels.toInt()
        if (delta == 0) return
        pendingPixels -= delta
        view.nestedScrollBy(0, delta)
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
                sampleSpan()
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
     * Marks the start and end of a FastScroller-driven gesture, which is what lets [scrollTo] treat the
     * first call as an absolute landing and every later one as a finger delta.
     *
     * ⚠️ DRIVEN OFF WHETHER FASTSCROLLER CONSUMED THE EVENT, NOT `view.scrollState`. FastScroller moves
     * the list itself, so RecyclerView's scrollState stays IDLE for the entire thumb drag and would never
     * open the gesture. FastScroller.onTouchEvent returns `mDragging`, so a consumed event means exactly
     * "it has the thumb or the track and is about to drive scrollTo".
     *
     * Ordering is deliberate and the track-TAP path depends on it: this runs AFTER the predicate, so on
     * the ACTION_MOVE where FastScroller decides a track tap has happened it calls scrollToThumbOffset
     * before [gestureActive] is set — which routes that first jump down [scrollTo]'s absolute branch,
     * where it belongs. A thumb GRAB sets the flag on ACTION_DOWN, and FastScroller issues no scrollTo
     * until the following ACTION_MOVE, so it is seeded by the NaN check instead.
     */
    private fun trackGesture(event: MotionEvent, consumed: Boolean) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (consumed) gestureActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureActive = false
                // Cleared together: a fraction or remainder left behind would be applied against the NEXT
                // gesture's span, which is a different scale.
                lastFraction = Double.NaN
                pendingPixels = 0.0
            }
        }
    }
}
