package dev.brahmkshatriya.echo.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.core.util.toKotlinPair
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.resolveStyledDimension
import kotlin.math.floor

interface GridAdapter {
    val adapter: RecyclerView.Adapter<*>
    fun getSpanSize(position: Int, width: Int, count: Int): Int

    // Lets VerticalSpacingItemDecoration skip adding a gap before items that already provide
    // their own visual separation (e.g. section headers), avoiding doubled spacing.
    fun isSectionHeader(position: Int): Boolean = false

    /**
     * Extra space below the item at [position] WHEN THE NEXT ITEM IS A SECTION HEADER. Ignored anywhere
     * else, which is the whole of its scope.
     *
     * ⚠️ NOT A GENERAL PER-ITEM SPACING HOOK, and it must not become one. Answer from the item's TYPE
     * (getItemViewType), never from its identity or index. It exists for exactly one reason: different
     * section types carry different intrinsic bottom padding in their own layouts, and the decoration is
     * where that gets equalised. Anything that is not "this KIND of section ends differently" belongs in
     * the decoration itself, or nowhere. Default 0, so every adapter that does not care is unaffected.
     *
     * This is a CONTINUATION of the 2026-06-23 uniform-spacing sweep (14d8bf7f), not a reversal of it.
     * That commit replaced ad-hoc per-layout padding with this decoration and deleted
     * item_shelf_lists.xml's `paddingBottom="4dp"` — but the carousel CHILD, item_shelf_lists_media.xml,
     * kept `android:padding="4dp"`, and that surviving residue is the entire reason a carousel and a card
     * grid end with different gaps today. Routing the difference through here moves the decision out of a
     * layout file and into the one mechanism that owns spacing. It centralises more, not less.
     */
    fun extraSpacingBeforeHeaderDp(position: Int): Int = 0

    class Concat(
        vararg adapters: GridAdapter
    ) : GridAdapter {
        override val adapter = ConcatAdapter(adapters.map { it.adapter })
        private val getSpanSizeMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::getSpanSize
        }.toMap()
        private val isSectionHeaderMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::isSectionHeader
        }.toMap()
        private val extraSpacingMap = adapters.mapIndexed { index, gridAdapter ->
            gridAdapter.adapter to gridAdapter::extraSpacingBeforeHeaderDp
        }.toMap()

        override fun getSpanSize(position: Int, width: Int, count: Int): Int {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val getSpanSize = getSpanSizeMap[adapter]
                ?: throw IllegalStateException("No span size function found for adapter: ${adapter.javaClass.name}")
            return getSpanSize(pos, width, count)
        }

        override fun isSectionHeader(position: Int): Boolean {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val isSectionHeader = isSectionHeaderMap[adapter] ?: return false
            return isSectionHeader(pos)
        }

        override fun extraSpacingBeforeHeaderDp(position: Int): Int {
            val (adapter, pos) = adapter.getWrappedAdapterAndPosition(position).toKotlinPair()
            val extraSpacing = extraSpacingMap[adapter] ?: return 0
            return extraSpacing(pos)
        }
    }

    class VerticalSpacingItemDecoration(
        private val spacingPx: Int, private val gridAdapter: GridAdapter
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || state.itemCount == 0) return
            val lm = parent.layoutManager as? GridLayoutManager
            val spanCount = lm?.spanCount ?: 1
            val lookup = lm?.spanSizeLookup
            val itemGroup = lookup?.getSpanGroupIndex(position, spanCount) ?: position
            // Find the rightmost adapter position in the same visual row. With caching
            // enabled this is O(1) per step; for spanCount=2 it checks at most 1 extra item.
            val lastInRow = runCatching {
                if (lookup != null) {
                    var last = position
                    while (last + 1 < state.itemCount &&
                        lookup.getSpanGroupIndex(last + 1, spanCount) == itemGroup) last++
                    last
                } else position
            }.getOrDefault(position)
            if (lastInRow >= state.itemCount - 1) return
            // TRACE (2026-09-04, temporary, GladixSpacing). REMOVE WITH THE 40dp PROBE.
            // Both runCatchings below degrade to a default, and BOTH Concat map lookups (isSectionHeaderMap,
            // extraSpacingMap) also return a default on a miss - four silent paths to 0, none distinguishable
            // from "the number is simply small" by looking at the device. These lines name which one ran.
            // A failure is logged with its throwable rather than counted, because the two candidates
            // (a stale-position IndexOutOfBounds vs an adapter-key miss) need telling apart.
            val headerNext = runCatching { gridAdapter.isSectionHeader(lastInRow + 1) }
            headerNext.exceptionOrNull()?.let {
                Log.d("GladixSpacing", "isSectionHeader THREW at ${lastInRow + 1}: $it")
            }
            if (headerNext.getOrDefault(false)) {
                // Asked of the item that is ENDING (position), not of the header. Same runCatching and the
                // same getWrappedAdapterAndPosition route as isSectionHeader above, so this adds no new
                // exposure to the stale-position throw that guard exists for.
                val extraResult = runCatching { gridAdapter.extraSpacingBeforeHeaderDp(position) }
                extraResult.exceptionOrNull()?.let {
                    Log.d("GladixSpacing", "extraSpacing THREW at $position: $it")
                }
                val extra = extraResult.getOrDefault(0)
                Log.d(
                    "GladixSpacing",
                    "header branch: pos=$position lastInRow=$lastInRow extra=$extra " +
                        "bottomDp=${HEADER_PRE_SPACING_DP + extra} (normal row bottom would be 8dp)"
                )
                outRect.bottom = (HEADER_PRE_SPACING_DP + extra).dpToPx(parent.context)
                return
            }
            outRect.bottom = spacingPx
        }

        companion object {
            // Baseline extra space before a section header, applied to EVERY section type, on top of
            // the header's own internal padding. Stays 0: the header's own 8dp inner marginVertical was
            // deemed sufficient for a single-row section, and raising this would widen every gap in the
            // app rather than the one type that needs it. Per-type adjustment goes through
            // GridAdapter.extraSpacingBeforeHeaderDp instead — see its note for why that is the right
            // lever and this is not.
            private const val HEADER_PRE_SPACING_DP = 0
        }
    }

    companion object {
        fun configureGridLayout(
            recycler: RecyclerView, gridAdapter: GridAdapter, even: Boolean = true
        ) {
            val context = recycler.context
            val isTV = (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
                .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            val layoutManager = GridLayoutManager(context, 1)
            recycler.adapter = gridAdapter.adapter
            recycler.layoutManager = layoutManager
            recycler.addItemDecoration(VerticalSpacingItemDecoration(8.dpToPx(context), gridAdapter))
            recycler.doOnLayout { view ->
                val itemWidth = if (isTV) {
                    val screenHeight = context.resources.displayMetrics.heightPixels
                    val miniPlayerHeight = 84.dpToPx(context)
                    val usableHeight = screenHeight - miniPlayerHeight
                    (usableHeight / 2.5f).toInt()
                } else context.resolveStyledDimension(R.attr.itemCoverSize)
                val width = view.width - view.paddingLeft - view.paddingRight
                val calc = floor(width.toFloat() / (itemWidth + 8.dpToPx(context))).toInt()
                val count = if (calc > 1) calc - if (even) calc % 2 else 0 else 1
                recycler.post {
                    layoutManager.spanCount = count
                    layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return gridAdapter.getSpanSize(position, width, count)
                        }
                    }
                    layoutManager.spanSizeLookup.setSpanGroupIndexCacheEnabled(true)
                    layoutManager.spanSizeLookup.setSpanIndexCacheEnabled(true)
                    recycler.requestLayout()
                }
            }
        }
    }
}