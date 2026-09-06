package dev.brahmkshatriya.echo.ui.media

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.updatePaddingRelative
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
import dev.brahmkshatriya.echo.utils.ui.UiUtils.resolveStyledDimension
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
            val toolbarPx = binding.recyclerView.context.resolveStyledDimension(R.attr.actionBarSize)
            binding.recyclerView.updatePaddingRelative(top = it.top + toolbarPx)
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