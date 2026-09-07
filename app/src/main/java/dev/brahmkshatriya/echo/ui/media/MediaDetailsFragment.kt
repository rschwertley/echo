package dev.brahmkshatriya.echo.ui.media

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.updatePaddingRelative
import com.google.android.material.color.MaterialColors
import com.google.android.material.appbar.MaterialToolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import kotlinx.coroutines.launch
import com.google.android.material.appbar.AppBarLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.databinding.FragmentMediaDetailsBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.brahmkshatriya.echo.ui.feed.FeedViewModel
import dev.brahmkshatriya.echo.ui.media.MediaHeaderAdapter.Companion.getMediaHeaderListener
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isTv
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper.applyInsets
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MediaDetailsFragment : Fragment(R.layout.fragment_media_details) {

    interface Parent {
        val feedId: String
        val viewModel: MediaDetailsViewModel
        val fromPlayer: Boolean
        val showInitialButtons: Boolean get() = false
    }

    val parent by lazy { requireParentFragment() as Parent }
    val viewModel by lazy { parent.viewModel }
    private val feedViewModel by lazy {
        requireParentFragment().viewModel<FeedViewModel>().value
    }

    private val trackFeedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_tracks",
            Feed.Buttons(showPlayAndShuffle = true),
            true,
            viewModel.tracksLoadedFlow, viewModel.trackCachedFlow,
            cached = { viewModel.trackCachedFlow.value?.getOrThrow() },
            loader = { viewModel.tracksLoadedFlow.value?.getOrThrow() }
        )
    }

    private val feedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_feed",
            Feed.Buttons.EMPTY,
            false,
            viewModel.feedCachedFlow, viewModel.feedLoadedFlow,
            cached = { viewModel.feedCachedFlow.value?.getOrThrow() },
            loader = { viewModel.feedLoadedFlow.value?.getOrThrow() }
        )
    }

    private val mediaHeaderAdapter by lazy {
        MediaHeaderAdapter(
            requireParentFragment().getMediaHeaderListener(viewModel),
            parent.fromPlayer
        )
    }

    private val feedListener by lazy {
        if (!parent.fromPlayer) requireParentFragment().getFeedListener()
        else FeedClickListener(
            requireParentFragment(),
            requireActivity().supportFragmentManager,
            R.id.navHostFragment
        ) {
            val uiViewModel by activityViewModel<UiViewModel>()
            uiViewModel.collapsePlayer()
        }
    }

    private val trackAdapter by lazy {
        getFeedAdapter(trackFeedData, feedListener, true)
    }
    private val feedAdapter by lazy {
        getFeedAdapter(feedData, feedListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMediaDetailsBinding.bind(view)
        // ⚠️ ORDER IS LOAD-BEARING: the ItemTouchHelper is attached BEFORE the fast scroller,
        // matching HomeFragment/LibraryFragment/SearchFragment. Both are OnItemTouchListeners and
        // RecyclerView.dispatchOnItemTouch walks mOnItemTouchListeners IN REGISTRATION ORDER, latching
        // the first that intercepts — upstream AndroidFastScroll issue #53. This file and FeedFragment
        // had the opposite order and were the only two screens where the fast-scroll thumb rendered but
        // refused to drag. Do not reorder these two lines.
        getTouchHelper(feedListener).attachToRecyclerView(binding.recyclerView)
        // Handle KEPT and re-padded below. Discarding it left the track on applyTo's flat 8dp forever,
        // running under the mini-player and nav bar.
        // COLLAPSING SCREEN: hand the scroller the AppBarLayout so its metrics account for the scroll
        // the header consumes. It lives in the PARENT fragment's layout (fragment_media.xml), not this
        // one, and is a sibling of this fragment's container under the CoordinatorLayout — hence resolved
        // from the parent rather than from binding.
        val appBar = requireParentFragment().view?.findViewById<AppBarLayout>(R.id.appBarLayout)
        val scroller = FastScrollerHelper.applyTo(binding.recyclerView, appBar, traceTag = "media")
        setupToolbarFade(appBar, binding.recyclerView)
        val uiViewModel by activityViewModel<UiViewModel>()
        applyInsets(viewModel.uiResultFlow, uiViewModel.tvMiniPlayerVisible) {
            val miniExtra = if (isRail && tvMiniPlayerVisible.value) 85.dpToPx(binding.recyclerView.context) else 0
            binding.recyclerView.applyContentInsets(it, 20, 0, 16 + miniExtra)
            // ⚠️ CONTENT PADDING AND TRACK PADDING NOW DISAGREE, DELIBERATELY. Since 2026-09-06 this
            // RecyclerView spans the FULL viewport and is drawn under the AppBarLayout
            // (OverlapScrollingViewBehavior — read the class doc before changing either line here).
            //   CONTENT must start below the toolbar, or the cover would sit under the back button at
            //     rest instead of only when scrolled. applyContentInsets above sets top = 0 (its third
            //     argument is a symmetric dp value, not an inset), so the real top padding is written
            //     here: the status-bar inset the AppBarLayout consumes via fitsSystemWindows, plus
            //     ?actionBarSize for the toolbar itself. clipToPadding is already false in
            //     fragment_media_details.xml, which is what lets the art scroll up through it.
            //   THE TRACK must NOT be inset — that is the entire point of the overlap. top = 0 keeps the
            //     fast-scroll rail spanning the whole screen with the thumb grabbable at the very top.
            // ⚠️ The `top = 0` below is UNCHANGED but its ORIGINAL REASON IS NOW FALSE and must not be
            // quoted back: it used to read "this RecyclerView sits BELOW the AppBarLayout, so it never
            // extends under the status bar". It does now. The value stays 0 for the new reason above, and
            // the old symptom it fixed (thumb parked mid-track at rest, because the track was inset by a
            // status bar the view did not contain) cannot recur while the view does contain it.
            // ⚠️ actionBarSize IS A THEME ATTRIBUTE, NOT A DIMEN — there is no R.dimen for it, and it has
            // to be resolved against the CONTEXT'S THEME at runtime. Written as R.attr.actionBarSize on
            // 2026-09-06 and it did not compile: android.nonTransitiveRClass=true (gradle.properties), so
            // this module's R holds only THIS module's resources and a library attr is not in it.
            //
            // ⚠️ androidx.appcompat.R.attr, NOT android.R.attr, and the difference is load-bearing. The
            // app theme is Theme.Material3Expressive…NoActionBar, an AppCompat descendant, so the value
            // the toolbar actually uses comes from the APPCOMPAT attr: @style/Toolbar sets
            // android:layout_height="?actionBarSize" (styles.xml), which resolves there. The FRAMEWORK
            // attr of the same name is a different entry that a NoActionBar theme need not set at all —
            // reading it could yield 0 and silently leave the content under the toolbar. This padding has
            // to match the toolbar's own height, so it must read the same attr the toolbar read.
            val toolbarPx = TypedValue().let { typed ->
                val theme = binding.recyclerView.context.theme
                if (theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, typed, true))
                    TypedValue.complexToDimensionPixelSize(
                        typed.data, binding.recyclerView.resources.displayMetrics
                    )
                else 0
            }
            // ⚠️ PHONE 0 / TV toolbarPx, AND THE SPLIT IS DELIBERATE (2026-09-07).
            //
            // PHONE: 0, so item 0 — the cover — starts at y=0 and reads as being at the very top of the
            // screen, travelling under the transparent toolbar as the page scrolls. That was the point of
            // the overlap; padding the list down to clear the toolbar put the visual order back exactly
            // where the migration had found it. The header's NON-COVER states are inset instead, per
            // state, via MediaHeaderAdapter.topInset — read that before changing this line.
            //
            // TV: the padding stays. The fast scroller is disabled on TV outright
            // (FastScrollerHelper.isFastScrollUsable -> !context.isTv()), so the overlap buys TV nothing
            // and would only cost it: RecyclerView.requestChildRectangleOnScreen scrolls a D-pad-focused
            // child into the PADDED area, and with 0 here a focused row could be parked under the bar
            // with no pointer to drag it back out. Keeping the inset leaves TV's geometry exactly as it
            // was before the 2026-09-05 migration in this respect — the only TV-visible change from this
            // whole line of work is the item title moving from the toolbar into the header content.
            val topPad = if (binding.recyclerView.context.isTv()) it.top + toolbarPx else 0
            binding.recyclerView.updatePaddingRelative(top = topPad)
            mediaHeaderAdapter.topInset = it.top + toolbarPx
            scroller.applyInsets(binding.recyclerView.context, it, top = 0)
        }
        val lineAdapter = LineAdapter()
        observe(trackFeedData.shouldShowEmpty) {
            lineAdapter.loadState = if (it) LoadState.Loading else LoadState.NotLoading(false)
        }
        observe(viewModel.uiResultFlow) { result ->
            mediaHeaderAdapter.result = result
        }
        binding.recyclerView.itemAnimator = null
        configureGridLayout(
            binding.recyclerView,
            GridAdapter.Concat(
                mediaHeaderAdapter,
                trackAdapter.withLoading(this, initialButtons = parent.showInitialButtons, onEditPlaylistClick = {
                    lifecycleScope.launch {
                        val state = viewModel.uiResultFlow.value?.getOrNull() ?: return@launch
                        val playlist = state.item as? Playlist ?: return@launch
                        if (!playlist.isEditable) return@launch
                        if (viewModel.extensionFlow.value?.isClient<PlaylistEditClient>() != true) return@launch
                        requireParentFragment().openFragment<EditPlaylistFragment>(
                            null, EditPlaylistFragment.getBundle(state.extensionId, playlist, state.loaded)
                        )
                    }
                }),
                lineAdapter,
                feedAdapter.withLoading(this)
            ),
        )
        // ⚠️ KNOWN DEFECT, PRE-EXISTING, NOT INTRODUCED BY THE 2026-09-05 HEADER MIGRATION.
        // This safe cast is ALWAYS NULL here: fragment_media_details.xml declares a plain
        // androidx.recyclerview.widget.RecyclerView, not TvAwareRecyclerView. The 2026-06-06 pass that
        // swapped four layouts to TvAwareRecyclerView covered fragment_home, fragment_library,
        // fragment_search and fragment_recycler_with_refresh — fragment_media_details was not among them.
        // So this D-pad nav-rail wiring has never run on artist, album or playlist detail on TV, and the
        // `as?` swallows it silently.
        // Deliberately left alone: changing the view class mid-migration would alter measurement and focus
        // behaviour on the exact screens being changed. Fix it as its own pass, and note there is no
        // layout-land-television variant of this file, so the swap would be in the single shared layout.
        (binding.recyclerView as? TvAwareRecyclerView)?.navRailView =
            requireActivity().findViewById(R.id.navRailContainer)
        val loadingFlow = viewModel.isRefreshingFlow
            .combine(trackFeedData.isRefreshingFlow) { a, b -> a || b }
                .combine(feedData.isRefreshingFlow) { a, b -> a || b }
        binding.swipeRefresh.run {
            configure()
            setOnRefreshListener { viewModel.refreshTracks() }
            var hasEverLoaded = false
            observe(loadingFlow) { isLoading ->
                if (!isLoading) hasEverLoaded = true
                isRefreshing = hasEverLoaded && isLoading
            }
        }
        if (parent.fromPlayer) {
            binding.swipeRefresh.isEnabled = false
            ViewCompat.setNestedScrollingEnabled(binding.recyclerView, true)
        }
    }

    /**
     * Fades the toolbar from transparent-over-artwork to a solid bar as the header scrolls under it, and
     * fades the toolbar title in on the same curve so the name appears only once the header's own copy has
     * gone. This is what the CollapsingToolbarLayout used to do for free.
     *
     * ⚠️ NO CollapsingToolbarLayout AND NO SCROLL FLAGS, deliberately. Those are what make
     * AppBarLayout.getTotalScrollRange() non-zero, and a non-zero range is incompatible with the overlap
     * that lets this RecyclerView span the screen — see OverlapScrollingViewBehavior for the decoded
     * arithmetic. This reproduces the one visible behaviour we actually wanted, and nothing else about a
     * CTL, which is the whole point.
     *
     * ⚠️ COLOUR IS ?navBackground, NOT ?colorSurface. That is the app's own bar-surface attribute
     * (attributes.xml), resolving to ?colorSurfaceContainer in the light theme and
     * ?colorSurfaceContainerLowest in the dark/AMOLED one (themes.xml), and it is what the nav bar styles
     * already tint with. ?colorSurface would be a near-miss that diverges in the AMOLED theme.
     *
     * ⚠️ bg_toolbar_scrim SURVIVES — it is layer 0 and stays visible at rest, which is the ONLY thing
     * keeping the back arrow and overflow legible over a bright cover once the title is gone. The solid
     * colour is layer 1, painted OVER it, animated 0..255. Do not replace the gradient with the solid.
     *
     * ⚠️ THE ALPHA GOES ON THE DRAWABLE LAYER, NOT ON THE VIEW. appBar.alpha would fade the navigation
     * icon and the overflow along with the background, which is exactly backwards — they must stay solid
     * throughout.
     *
     * ⚠️ THE FADE IS DRIVEN BY ITEM 0'S REAL BOTTOM, not by a distance constant. The header's height
     * changes with the cover size, the title's line count and the artist page's larger cover, and reading
     * the laid-out bottom means none of that needs a second number kept in sync. When position 0 is
     * recycled away entirely the fraction pins at 1.
     *
     * SAFE FOR THE RAIL: addOnScrollListener is a different registry from the addOnItemTouchListener the
     * fast scroller uses, so the listener-ORDER hazard recorded in FastScrollerHelper does not apply here.
     * Nothing about measurement, padding or the overlay changes.
     */
    private fun setupToolbarFade(appBar: AppBarLayout?, recyclerView: RecyclerView) {
        appBar ?: return
        val toolBar = requireParentFragment().view?.findViewById<MaterialToolbar>(R.id.toolBar) ?: return
        val solid = ColorDrawable(MaterialColors.getColor(appBar, R.attr.navBackground)).apply {
            alpha = 0
        }
        // Wraps whatever the layout set rather than replacing it, so @drawable/bg_toolbar_scrim stays the
        // single source of the rest state and the XML keeps meaning what it says.
        val scrim = appBar.background
        appBar.background = LayerDrawable(listOfNotNull(scrim, solid).toTypedArray())
        val titleColor = MaterialColors.getColor(toolBar, com.google.android.material.R.attr.colorOnSurface)

        var lastAlpha = -1
        fun update() {
            val header = recyclerView.layoutManager?.findViewByPosition(0)
            // Ramp over the bar's own height: the fade completes exactly as the header's last pixel passes
            // under it. appBar.bottom and a child's bottom share an origin here — the CoordinatorLayout and
            // the (translated) RecyclerView both start at the top of the screen.
            val distance = appBar.height.coerceAtLeast(1)
            val fraction = if (header == null) 1f
            else (1f - (header.bottom - appBar.bottom).toFloat() / distance).coerceIn(0f, 1f)
            val alpha = (fraction * 255).toInt()
            if (alpha == lastAlpha) return
            lastAlpha = alpha
            solid.alpha = alpha
            toolBar.setTitleTextColor(ColorUtils.setAlphaComponent(titleColor, alpha))
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = update()
        })
        // Scroll alone is not enough: the header arrives asynchronously (the item result binds after the
        // view is created) and the page can be opened already at rest, where no scroll event ever fires.
        // A layout listener catches both, and the lastAlpha guard makes the repeat cost two int compares.
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        update()
    }
}
