package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
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
