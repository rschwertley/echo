package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder


object FastScrollerHelper {
    const val SCROLL_BAR = "scroll_bar"
    fun View.isScrollBarEnabled() = context.getSettings().getBoolean(SCROLL_BAR, false)

    /**
     * The fast scroller is a DRAG-TO-SCROLL affordance, so it is never applied on TV regardless of the
     * setting: D-pad is the only input there and there is no pointer to grab the thumb with. Without this
     * a TV user who found the switch got a thumb that drew and auto-hid on scroll but could never be
     * touched - FastScroller drives everything from an OnItemTouchListener, and a D-pad generates no
     * MotionEvents.
     *
     * SettingsLookFragment hides the preference on TV too. Both are needed: the preference hide stops it
     * being turned on, this stops an already-set value (or one synced from a phone) taking effect.
     *
     * Focus safety was NOT the reason - it was checked and is a non-issue. FastScroller adds its thumb,
     * track and popup through ViewGroupOverlay.add(), not addView(), so they are never children of the
     * RecyclerView, never enter the focus tree, and cannot fight TvAwareRecyclerView's establishFeedFocus
     * / anchorFocusAt anchoring on the same recyclers.
     */
    private fun View.isFastScrollUsable() = !context.isTv() && isScrollBarEnabled()

    /**
     * Keeps the scroller's TRACK inside the real window insets. Call from the same inset block that pads
     * the RecyclerView, so the two move together.
     *
     * `applyTo` sets a flat 8dp on all four sides once, and that is all a call site gets if it throws the
     * returned [FastScroller] away — which every direct caller did, so on those screens the track ran under
     * the mini-player and the nav bar. `MainFragment.applyInsets` was the only place that kept the handle
     * and re-padded it, which is exactly why the four tab screens were correct and the other seven were
     * not. This is that block, extracted so both routes share one implementation.
     *
     * ⚠️ THIS MOVES WHERE THE TRACK IS DRAWN. IT DOES NOT CHANGE THE SCROLL RANGE. If a screen scrolls
     * past its last row into blank space, that is a metrics problem and this will not fix it - see
     * PixelFastScrollViewHelper.
     *
     * Null-receiver tolerant: `applyTo` returns null whenever the scroller is not applied (setting off, or
     * on TV), so call sites can wire this unconditionally instead of guarding.
     */
    /**
     * Pads the scroll TRACK so it clears the system bars and the mini-player.
     *
     * ⚠️ [top] IS A PARAMETER BECAUSE THE ANSWER DIFFERS BY LAYOUT, and getting it wrong is invisible
     * until someone looks closely at where the thumb sits. It defaults to [UiViewModel.Insets.top], which
     * is right for a FULL-BLEED list — Home, Search, Library, History put the RecyclerView under the
     * status bar, so the track must start below it.
     *
     * It is WRONG for a list inside a CoordinatorLayout + AppBarLayout (MediaDetailsFragment,
     * FeedFragment). There, HeaderScrollingViewBehavior sizes the RecyclerView to roughly
     * `viewport - collapsedToolbarHeight` and CoordinatorLayout positions it BELOW the header, so the
     * status bar belongs to the AppBarLayout and not to this view. Adding it anyway inset the track by a
     * status bar that is not there: the track started too low and was too short, so the thumb sat below
     * the top of its own track at scroll zero (looking "parked mid-track") and the thumb-to-content
     * mapping was compressed by that amount. Those sites pass `top = 0`.
     *
     * THE TEST TO APPLY at any new call site: does the RecyclerView itself extend under the status bar?
     * The same question `View.applyContentInsets` answers with its `vertical` argument — and the two must
     * agree about the same view, since they are padding the content and the track of one list. Where
     * applyContentInsets is given a top of 0, this must be too.
     *
     * ⚠️ Fixes the thumb's POSITION only. The collapsing-layout screens ALSO had a dead drag, and that was
     * an unrelated OnItemTouchListener registration-order problem — see the note at those call sites.
     */
    fun FastScroller?.applyInsets(
        context: Context,
        insets: UiViewModel.Insets,
        extraBottom: Int = 0,
        top: Int = insets.top,
    ) {
        this ?: return
        val pad = 8.dpToPx(context)
        val isRtl = context.isRTL()
        // FLUSH ON THE THUMB SIDE. FastScroller lays the thumb at
        // `isLayoutRtl ? padding.left : viewWidth - padding.right - mThumbWidth`, so the thumb rides the
        // END padding in both directions — the swap below is what keeps that true in RTL. It gets the
        // window inset only, with NO 8dp: the inset is real (nav rail, cutout) but the 8dp was a cosmetic
        // gap, and at 40dp wide against a carousel it read as the thumb floating off the edge.
        //
        // TOP AND BOTTOM ARE UNCHANGED and must stay so — they are not cosmetic. insets.top clears the
        // status bar / app bar, and insets.bottom + extraBottom carries the nav bar and the mini-player,
        // which is what stops the track running underneath it.
        val edge = insets.end
        val far = insets.start + pad
        val left = if (!isRtl) far else edge
        val right = if (!isRtl) edge else far
        setPadding(Rect(left, top + pad, right, insets.bottom + extraBottom + pad))
    }

    /**
     * Echo's fast-scroller look: a small circular thumb and no visible track, in place of
     * AndroidFastScroll's md2 style (8dp x 52dp rounded-rect thumb over a full-height 8dp bar).
     *
     * ⚠️ MUST be called AFTER useMd2Style(). That call sets BOTH drawables, so anything applied
     * before it is overwritten.
     *
     * Only the drawables change. Auto-hide-on-idle, drag, and tap-to-jump are all untouched - do NOT
     * call disableScrollbarAutoHide(), the fade when idle is the behaviour we want. There is no popup
     * to style: AndroidFastScroll only shows one when the adapter implements PopupTextProvider, and
     * none of ours do.
     *
     * See the two drawable files for why the track is transparent rather than removed, and why it
     * cannot be a ColorDrawable.
     *
     * KNOWN COST, carried here because this is where someone will look. FastScroller hit-tests the
     * track for tap-to-jump and expands every hit area to `afs_min_touch_target_size` (48dp), and
     * onTouchEvent is gated only on "is this list scrollable" - NOT on whether the thumb is currently
     * faded in. So a ~48dp strip along the edge is live on every scrollable list whenever the setting
     * is on. That predates this styling and is not caused by hiding the track.
     *
     * It matters because `more` (the ⋯ overflow) is the rightmost control on every media row -
     * item_shelf_media, item_history, item_shelf_video - so the strip overlaps it.
     *
     * ✅ TESTED ON DEVICE 2026-09-04 — ACCEPTABLE, AND THIS GATE IS CLOSED. Do not re-run it, and do not
     * reach for the dimen override below. The reasoning above OVERSTATED the reach: the collision is
     * THUMB-POSITION-ONLY, not track-wide. Only the ⋯ on the single row the thumb is physically sitting
     * in front of is unreachable; rows at the same right edge above and below it open their menus
     * normally. So the cost is one row briefly blocked by a VISIBLE control, cleared by scrolling - not
     * an invisible strip eating taps down the whole edge, which is what the paragraph above predicted and
     * what would genuinely have argued against default-on.
     *
     * ➤ THE FIX, NOT APPLIED AND NOT NEEDED: `afs_min_touch_target_size` is read with
     *   Resources.getDimensionPixelSize, so an app-side <dimen> of that name overrides the library's 48dp
     *   by normal resource merging - no fork required. It shrinks the THUMB's grab area too and takes it
     *   under the 48dp accessibility floor, which is why it stays unapplied: the measured cost does not
     *   justify it. Kept here only so the option is documented if the behaviour ever changes upstream.
     */
    private fun FastScrollerBuilder.applyEchoStyle(context: Context) {
        setTrackDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_track)!!)
        setThumbDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_thumb)!!)
    }

    fun applyTo(view: RecyclerView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isFastScrollUsable()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            // Replaces the library's RecyclerViewHelper, which estimates every item's height from
            // getChildAt(0) alone and so makes the thumb race, snap back and vanish on any screen with
            // mixed row heights. See PixelFastScrollViewHelper for the ViewHelper contract, for why
            // RecyclerView's own averaged estimates satisfy it, and for why the earlier position-based
            // attempt could not. RecyclerView ONLY — the NestedScrollView overload below keeps the
            // library's default helper, which is already this shape there.
            setViewHelper(PixelFastScrollViewHelper(view))
            // Pre-inset default; applyInsets overwrites it on the first inset pass. Flush on the thumb
            // side here too so the first frame does not show the gap and then close it.
            val pad = 8.dpToPx(view.context)
            val isRtl = view.context.isRTL()
            setPadding(if (isRtl) 0 else pad, pad, if (isRtl) pad else 0, pad)
        }.build()
    }

    fun applyTo(view: NestedScrollView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isFastScrollUsable()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

}
