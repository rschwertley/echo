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
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder


object FastScrollerHelper {
    const val SCROLL_BAR = "scroll_bar"
    fun View.isScrollBarEnabled() = context.getSettings().getBoolean(SCROLL_BAR, false)

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
     * ➤ TEST THIS before the SCROLL_BAR default is ever flipped to true: scroller on, tap ⋯ near the
     *   right edge of a feed row in a long list, and see whether the menu opens or the tap is eaten.
     *   If it is eaten, that is a stronger argument against default-on than upstream issue #53.
     * ➤ THE FIX, if needed: `afs_min_touch_target_size` is read with Resources.getDimensionPixelSize,
     *   so an app-side <dimen> of that name overrides the library's 48dp by normal resource merging -
     *   no fork required. It shrinks the THUMB's grab area too, and takes it under the 48dp
     *   accessibility floor, so do not apply it pre-emptively.
     */
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
     * PositionFastScrollViewHelper, whose range is derived from adapter positions and so does not include
     * the recycler's padding at all.
     *
     * Null-receiver tolerant: `applyTo` returns null whenever the SCROLL_BAR setting is off, so call sites
     * can wire this unconditionally instead of guarding.
     */
    fun FastScroller?.applyInsets(
        context: Context, insets: UiViewModel.Insets, extraBottom: Int = 0
    ) {
        this ?: return
        val pad = 8.dpToPx(context)
        val isRtl = context.isRTL()
        val left = if (!isRtl) insets.start else insets.end
        val right = if (!isRtl) insets.end else insets.start
        setPadding(Rect(left + pad, insets.top + pad, right + pad, insets.bottom + extraBottom + pad))
    }

    private fun FastScrollerBuilder.applyEchoStyle(context: Context) {
        setTrackDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_track)!!)
        setThumbDrawable(AppCompatResources.getDrawable(context, R.drawable.fast_scroll_thumb)!!)
    }

    fun applyTo(view: RecyclerView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isScrollBarEnabled()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            // Replaces the library's RecyclerViewHelper, whose scroll metrics extrapolate the whole list
            // from the height of ONE child and so make the thumb race, snap back and vanish on any screen
            // with mixed row heights. See PositionFastScrollViewHelper for the full mechanism and for why
            // the row-counting alternative was rejected. RecyclerView ONLY — the NestedScrollView overload
            // below keeps the library's default helper, which is correct there (a single scrolling child
            // has a real pixel range, so there is nothing to extrapolate).
            setViewHelper(PositionFastScrollViewHelper(view))
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

    fun applyTo(view: NestedScrollView): FastScroller? {
        view.isVerticalScrollBarEnabled = false
        if (!view.isScrollBarEnabled()) return null
        return FastScrollerBuilder(view).apply {
            useMd2Style()
            applyEchoStyle(view.context)
            val pad = 8.dpToPx(view.context)
            setPadding(pad, pad, pad, pad)
        }.build()
    }

}
