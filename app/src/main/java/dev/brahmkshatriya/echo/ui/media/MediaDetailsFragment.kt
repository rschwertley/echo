package dev.brahmkshatriya.echo.ui.media

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
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
        // ⚠⚠ setupToolbarFade REMOVED 2026-09-07, WITH THE OVERLAP. It faded a solid colour in behind the
        // toolbar and ramped the toolbar title's alpha as the header slid under the bar. IT CANNOT WORK
        // HERE ANY MORE, for two independent reasons, and neither is a bug to fix:
        //   1. NOTHING PASSES UNDER THE TOOLBAR. The bar is opaque and the list is positioned below it by
        //      ScrollingViewBehavior, so there is no rest state to fade FROM and nothing to fade AGAINST.
        //   2. ITS ARITHMETIC ASSUMED A SHARED ORIGIN. It computed
        //      `1 - (header.bottom - appBar.bottom) / appBar.height`, which was only meaningful while the
        //      CoordinatorLayout and the RecyclerView both started at y=0. The RecyclerView now starts
        //      below the bar, so the two are in different coordinate spaces and the fraction is wrong at
        //      every scroll position. It also wrapped appBar.background in a LayerDrawable to preserve
        //      @drawable/bg_toolbar_scrim as the rest state — and that scrim is gone too.
        // It never worked on device regardless: symptom 4 of the five was that the toolbar title stayed
        // solid the whole time and doubled with the header title mid-scroll.
        // [CORRECTED 2026-09-07] This note previously read "OPEN, NOT A DEFECT: MediaFragment still sets
        // binding.toolBar.title … deliberately left alone". IT NO LONGER DOES — that line was removed the
        // same day; see the note in its place in MediaFragment. The toolbar carries no title on these
        // pages, so there is nothing to fade and nothing to double.
        // STILL OPEN: once the header scrolls past, the bar is empty. A scroll-driven HANDOFF is the fix if
        // that reads badly, and it does NOT require the overlap — but it cannot reuse the arithmetic above.
        // What it needs instead: compare the header title's bottom to the RecyclerView's OWN top, entirely
        // inside the list's coordinate space, rather than comparing a child's bottom to appBar.bottom
        // across two spaces. findViewByPosition(0) -> findViewById(R.id.title) -> bottom <= 0 means passed.
        // Null header (recycled away, or not yet bound) means passed, same as the old `header == null` arm.
        // Needs the same two triggers the old one used — addOnScrollListener AND addOnLayoutChangeListener,
        // because the item result binds asynchronously and a page can open already at rest with no scroll
        // event ever firing.
        val uiViewModel by activityViewModel<UiViewModel>()
        applyInsets(viewModel.uiResultFlow, uiViewModel.tvMiniPlayerVisible) {
            val miniExtra = if (isRail && tvMiniPlayerVisible.value) 85.dpToPx(binding.recyclerView.context) else 0
            binding.recyclerView.applyContentInsets(it, 20, 0, 16 + miniExtra)
            // ⚠⚠ TRIED AND REVERTED 2026-09-07 — NO TOP PADDING HERE, AND THAT IS THE POINT. ⚠⚠
            // For one build (1085) this RecyclerView spanned the full viewport under a transparent toolbar
            // (OverlapScrollingViewBehavior on the container in fragment_media.xml) and wrote its own
            // paddingTop here — `if (isTv()) it.top + toolbarPx else 0`, with a TypedValue lookup of
            // androidx.appcompat.R.attr.actionBarSize and a matching MediaHeaderAdapter.topInset. Read the
            // five device symptoms recorded in fragment_media.xml before reviving any of it.
            //
            // ⚠️ WHY THE REVERT IS "NO PADDING" AND NOT "PADDING RESTORED TO insets.top + ?actionBarSize",
            // WHICH IS THE OBVIOUS-SOUNDING FIX AND WOULD BE A NEW DEFECT: with
            // @string/appbar_scrolling_view_behavior back on the container, ScrollingViewBehavior POSITIONS
            // the whole view below the AppBarLayout. The toolbar clearance is already paid, once, by
            // layout. Adding a toolbar-height padding on top of that would inset the content TWICE and
            // push the cover down by a full bar height. Verified against the pre-overlap file
            // (e787edd6^): there was no updatePaddingRelative and no toolbarPx at all — only
            // applyContentInsets(it, 20, 0, 16 + miniExtra) with its third argument 0, exactly as now.
            //
            // The `top = 0` on the scroller line below is UNCHANGED and its ORIGINAL reason is true again:
            // this RecyclerView sits BELOW the AppBarLayout, so it never extends under the status bar and
            // the track must not be inset for one. (The overlap build kept the same 0 for the opposite
            // reason. Same value, different justification — do not quote the overlap-era wording back.)
            //
            // KNOWN COST OF THE REVERT, ACCEPTED DELIBERATELY: the fast-scroll rail begins below the
            // toolbar strip again, so the thumb's resting position is not at the very top of the screen.
            // That is the problem the overlap was built to solve. It is smaller than any one of the five
            // symptoms the overlap caused — and symptom 5 was that the thumb ended up UNGRABBABLE behind
            // the toolbar anyway, so the overlap did not actually deliver the benefit it cost five defects
            // to attempt.
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

}
