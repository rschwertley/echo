package dev.brahmkshatriya.echo.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.databinding.FragmentGenericCollapsableBinding
import dev.brahmkshatriya.echo.databinding.FragmentRecyclerWithRefreshBinding
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.cache.Cached.savingFeed
import dev.brahmkshatriya.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.brahmkshatriya.echo.ui.common.TvAwareRecyclerView
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.configure
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.ui.extensions.login.LoginFragment.Companion.bind
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.brahmkshatriya.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.brahmkshatriya.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.brahmkshatriya.echo.ui.main.MainFragment.Companion.applyPlayerBg
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.FastScrollerHelper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class FeedFragment : Fragment(R.layout.fragment_generic_collapsable) {
    companion object {
        // Prefetch debounce. Below ~400ms a fling that momentarily settles starts warming; above ~600ms a
        // genuine pause gets missed. 500 is the point where a fast scroll costs zero requests.
        const val WARM_DEBOUNCE_MS = 500L

        fun getBundle(title: String, subtitle: String?) = Bundle().apply {
            putString("title", title)
            putString("subtitle", subtitle)
        }
    }

    class VM : ViewModel() {
        var initialized = false
        var extensionId: String? = null
        var feedId: String? = null
        var feed: Feed<Shelf>? = null
    }

    private val activityVm by activityViewModels<VM>()
    private val vm by viewModels<VM>()

    private val feedData by lazy {
        val feedViewModel by viewModel<FeedViewModel>()
        if (!vm.initialized) {
            vm.initialized = true
            vm.extensionId = activityVm.extensionId
            vm.feedId = activityVm.feedId
            vm.feed = activityVm.feed
        }
        feedViewModel.getFeedData(
            vm.feedId ?: "",
            cached = {
                val extId = vm.extensionId!!
                val feed = Cached.getFeedShelf(app, extId, vm.feedId!!)
                FeedData.State(extId, null, feed.getOrThrow())
            }
        ) {
            val extension = music.getExtensionOrThrow(vm.extensionId)
            val feed = savingFeed(app, extension, vm.feedId!!, vm.feed!!)
            FeedData.State(extension.id, null, feed)
        }
    }

    private val title by lazy { arguments?.getString("title")!! }
    private val subtitle by lazy { arguments?.getString("subtitle") }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentGenericCollapsableBinding.bind(view)
        binding.bind(this, false)
        binding.extensionIcon.isVisible = false
        binding.toolBar.title = title
        binding.toolBar.subtitle = subtitle
        // TV rail parity (matches MediaFragment): the collapsable HEADER (appBarLayout) is otherwise
        // rail-unaware and stretches full-width across the nav rail, while the list already insets via
        // `combined`. Pad the header start by the rail inset so it lines up with the list. isRail-gated,
        // so on phone (isRail == false) this observer never registers and the header is untouched.
        val uiViewModel by activityViewModel<UiViewModel>()
        if (uiViewModel.isRail) observe(uiViewModel.combined) {
            binding.appBarLayout.updatePaddingRelative(start = it.start)
        }
        applyPlayerBg(view) {
            mainBgDrawable.combine(feedData.backgroundImageFlow) { a, b -> b ?: a }
        }
        if (savedInstanceState == null) childFragmentManager.commit {
            replace<Actual>(R.id.genericFragmentContainer, null, arguments)
        }
    }

    class Actual : Fragment(R.layout.fragment_recycler_with_refresh) {
        private val feedData by lazy {
            val vm by requireParentFragment().viewModel<FeedViewModel>()
            vm.feedDataMap.values.first()
        }

        private val listener by lazy { requireParentFragment().getFeedListener() }
        private val feedAdapter by lazy {
            getFeedAdapter(feedData, listener)
        }
        private var swipeRefresh: SwipeRefreshLayout? = null

        override fun onHiddenChanged(hidden: Boolean) {
            super.onHiddenChanged(hidden)
            if (hidden) swipeRefresh?.isRefreshing = false
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val binding = FragmentRecyclerWithRefreshBinding.bind(view)
            val recyclerView = binding.recyclerView as RecyclerView
            val uiViewModel by activityViewModel<UiViewModel>()
            applyInsets(uiViewModel.tvMiniPlayerVisible) {
                val miniExtra = if (isRail && tvMiniPlayerVisible.value) 85.dpToPx(recyclerView.context) else 0
                recyclerView.applyContentInsets(it, 20, 8, 16 + miniExtra)
            }
            FastScrollerHelper.applyTo(recyclerView)
            configureGridLayout(
                recyclerView,
                feedAdapter.withLoading(this),
            )
            (recyclerView as? TvAwareRecyclerView)?.navRailView =
                requireActivity().findViewById(R.id.navRailContainer)
            getTouchHelper(listener).attachToRecyclerView(recyclerView)

            // ══ PREFETCH TRIGGER ══ See FeedData.warmTracks for the gates and the reasoning.
            // Scroll-settle, not long-press: what predicts opening an item is looking at it. A long-press
            // opens the more-menu and is used to queue, to check the artist, or by accident - it does not
            // predict an open, and it was only ever considered because the listener already existed.
            //
            // The debounce is what makes a fling cost ZERO: every state change cancels the pending job, so
            // a scroll that never settles for the full delay warms nothing. It also cancels a settle that
            // is immediately scrolled away from.
            //
            // Cancelling the JOB does not cancel a warm already in flight, and that is deliberate: once
            // started, the request is owned by app.scope inside coalescedTracks, so it completes and fills
            // the memory layer. Cancelling mid-response would waste the bytes already transferred AND cache
            // nothing, turning a partially-useful request into a wholly wasted one.
            //
            // CENTRE-WEIGHTED MIDDLE THREE rather than every visible row: the middle of the viewport is
            // what someone who stopped scrolling is looking at, and three keeps a deliberate pause at about
            // three requests instead of six. The visible width is the tuning knob if that needs adjusting.
            val playerViewModel by activityViewModel<PlayerViewModel>()
            var warmJob: Job? = null
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    warmJob?.cancel()
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    warmJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(WARM_DEBOUNCE_MS)
                        // GridLayoutManager extends LinearLayoutManager, so this covers both feed layouts.
                        val lm = rv.layoutManager as? LinearLayoutManager ?: return@launch
                        val first = lm.findFirstVisibleItemPosition()
                        val last = lm.findLastVisibleItemPosition()
                        if (first < 0 || last < first) return@launch
                        val centre = (first + last) / 2
                        val visible = (centre - 1..centre + 1)
                            .filter { it in first..last }
                            // peek, never get: reading an item must not trigger a Paging load.
                            .mapNotNull { runCatching { feedAdapter.peek(it) }.getOrNull() }
                        feedData.warmTracks(visible, playerViewModel.isPlaying.value)
                    }
                }
            })
            swipeRefresh = binding.swipeRefresh
            binding.swipeRefresh.run {
                configure()
                setOnRefreshListener { feedData.refresh() }
                var hasEverLoaded = false
                observe(feedData.isRefreshingFlow) {
                    if (!it) hasEverLoaded = true
                    isRefreshing = hasEverLoaded && it
                }
            }
        }
    }
}