package dev.brahmkshatriya.echo.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
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
            if (runCatching { gridAdapter.isSectionHeader(lastInRow + 1) }.getOrDefault(false)) {
                outRect.bottom = HEADER_PRE_SPACING_DP.dpToPx(parent.context)
                return
            }
            outRect.bottom = spacingPx
        }

        companion object {
            // THE PRE-HEADER GAP, FOR EVERY SECTION TYPE. This REPLACES the row spacing rather than
            // adding to it (note the `return` above), so it is the WHOLE decoration contribution before a
            // header — a value of 0 means the gap is the header's own 8dp marginVertical and nothing else.
            //
            // ⚠️ ONE VALUE, DELIBERATELY. A per-type hook (extraSpacingBeforeHeaderDp) lived here until
            // 2026-09-04 and was deleted rather than zeroed: it COMPENSATED for section types ending
            // differently instead of making them end the same, so every new section type needed a new
            // number and the numbers could only ever be tuned against each other. The differences it was
            // compensating for were 4dp of vertical padding in three layouts (the carousel card, video,
            // video-horizontal), all now normalised to zero, plus a variable amount of unconsumed row
            // reservation in carousels, now gone because HorizontalListViewHolder MEASURES the row instead
            // of reserving a dimen. With every type contributing nothing of its own, this constant is the
            // only lever and it moves all of them together.
            //
            // If a section type ever ends with the wrong gap again, the fix is to find what padding that
            // type carries and remove it — not to reintroduce a per-type exception here.
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
            // ── SPAN-AWARE SCROLLBAR ESTIMATION (2026-09-05) ────────────────────────────────────────
            // GridLayoutManager ships two estimators and defaults to the WRONG ONE for a mixed feed.
            // Default (ScrollbarHelper.computeScrollRange): laidOutArea / itemRange * itemCount
            // Enabled  (computeScrollRangeWithSpanInfo):    laidOutArea / laidOutSpans * totalSpans
            // The difference is the divisor: ITEMS versus SPAN GROUPS. On Home, Search and media pages,
            // full-span shelves sit beside 2-up tiles — two tiles are TWO ITEMS BUT ONE ROW — so the
            // default's per-item figure roughly halves whenever tile rows are on screen and doubles back
            // when shelves are. That is the mechanism behind the measured 3415→5371 range swing (57%) on a
            // static artist page, and behind Search's deterministic stall two-thirds down, where the
            // estimate grows as denser rows attach mid-drag and offset/range plateaus.
            //
            // ⚠️ IT MOVES RANGE AND OFFSET TOGETHER, and that is why it is safe to enable globally.
            // computeScrollOffsetWithSpanInfo is the paired method — both switch on this one flag — so the
            // `offset + extent == range` identity that puts the fast-scroll thumb exactly at the rail
            // bottom cannot be broken by enabling it. A range-only change (e.g. a measured row-height
            // cache over liveRange()) WOULD break it, which is why that larger proposal was not taken.
            //
            // ⚠️ NO-OP ON SINGLE-COLUMN SCREENS, by construction rather than by test: with spanCount 1 and
            // every item spanning 1, getCachedSpanGroupIndex(pos, 1) == pos, so totalSpans == itemCount and
            // laidOutSpans == itemRange — the two expressions are arithmetically identical. Screens that
            // resolve to one column compute exactly what they computed before. (HistoryFragment is not
            // affected at all: fragment_history.xml uses a LinearLayoutManager and never calls this.)
            //
            // ⚠️ STILL AN EXTRAPOLATION. It divides by a better denominator; it does not measure content.
            // The range will still move as the laid-out window changes, so it should SHRINK the swing, not
            // remove it. If a capture shows the Search stall unchanged at the same row, row density is not
            // the driver — and that also weakens the case for the row-height cache, which attacks the same
            // quantity from the same premise.
            //
            // Requires the span-group index cache, which is already enabled below.
            layoutManager.isUsingSpansToEstimateScrollBarDimensions = true
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