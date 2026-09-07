package dev.brahmkshatriya.echo.ui.media

import android.os.Bundle
import android.view.View
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.databinding.FragmentMediaBinding
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyGradient
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.icon
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.playlist.delete.DeletePlaylistBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.Serializer.getSerialized
import dev.brahmkshatriya.echo.utils.Serializer.putSerialized
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.setupTransition
import dev.brahmkshatriya.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MediaFragment : Fragment(R.layout.fragment_media), MediaDetailsFragment.Parent {
    companion object {
        fun getBundle(extensionId: String, item: EchoMediaItem, loaded: Boolean) = Bundle().apply {
            putString("extensionId", extensionId)
            putSerialized("item", item)
            putBoolean("loaded", loaded)
        }
    }

    val args by lazy { requireArguments() }
    val extensionId by lazy { args.getString("extensionId")!! }
    val item by lazy { args.getSerialized<EchoMediaItem>("item")!!.getOrThrow() }
    val loaded by lazy { args.getBoolean("loaded") }

    override val fromPlayer = false
    override val feedId by lazy { item.id }
    override val showInitialButtons get() = item !is Artist

    override val viewModel by viewModel<MediaViewModel> {
        parametersOf(true, extensionId, item, loaded, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMediaBinding.bind(view)
        setupTransition(view)
        applyBackPressCallback()
        // coverContainer.alpha = 1 - offset removed 2026-09-05 with the CollapsingToolbarLayout: the
        // cover now lives in MediaHeaderAdapter (item 0) and there is no collapse to fade against.
        // `offset` is still meaningful for the outline — a non-scrolling AppBarLayout reports 0, so the
        // outline simply stays hidden until something scrolls, which is the behaviour it already had at
        // rest.
        binding.appBarLayout.configureAppBar { offset ->
            binding.appbarOutline.alpha = offset
        }
        // Landscape (nav rail) only: the CTL/AppBar header is otherwise rail-unaware, so its
        // content (cover, back button, collapsed + expanded title) sits behind the left rail.
        // Pad the AppBar start by the rail inset so the whole header shifts into the content
        // area, matching how the detail list already insets via `combined`. start-only:
        // fitsSystemWindows still owns the top status-bar inset (no duplication). Portrait has
        // isRail == false, so this observer is never registered and the header is untouched.
        val uiViewModel by activityViewModel<UiViewModel>()
        if (uiViewModel.isRail) observe(uiViewModel.combined) {
            binding.appBarLayout.updatePaddingRelative(start = it.start)
        }
        binding.toolBar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.toolBar.setOnMenuItemClickListener {
            val item = viewModel.itemResultFlow.value?.getOrNull()?.item ?: item
            MediaMoreBottomSheet.show(
                this, parentFragmentManager,
                id, extensionId, item, !viewModel.isRefreshing
            )
            true
        }
        // ⚠️ `if (isTV) appBarLayout.setExpanded(false, false)` REMOVED 2026-09-05, not lost. It existed so
        // TV opened with the header collapsed and content D-pad-reachable without scrolling past a
        // full-size cover. Two reasons it goes rather than being replaced:
        //   1. It is now INERT. With no `scroll` flag on any AppBarLayout child, getTotalScrollRange()
        //      returns 0 (decoded from the Material 1.14.0 AAR), so there is nothing to collapse. Leaving
        //      the call would read as active TV handling while doing nothing.
        //   2. The need it served should now be met by focus-driven scrolling: the cover is a CardView
        //      with no click listener and is not focusable, while item 0's buttons are — so the first
        //      D-pad focus lands on a button and RecyclerView scrolls it into view on its own.
        // ⚠️ (2) WAS REASONED, NOT MEASURED — and as written on 2026-09-05 its premise was wrong: the
        // AppBarLayout was child 0 of fragment_media.xml, and ViewGroup.onRequestFocusInDescendants
        // (android-34 sources, :3355-3380) walks mChildren in RAW INDEX ORDER, so the first D-pad focus
        // landed on the toolbar's navigation icon, not on a button. The 2026-09-06 overlay-toolbar change
        // reorders that file so the FragmentContainerView is child 0, which makes the claim true. Still
        // verify on a TV that opening an artist/album lands focus on a button rather than stranding it
        // above a full-height cover. If it does strand, the fix is a
        // values-land-television override of @dimen/media_header_cover_size (that folder already exists),
        // NOT scrollToPosition(1) — position 1 skips item 0 entirely and takes the buttons off screen with
        // it, which the collapsed header never did.

        observe(viewModel.itemResultFlow) { result ->
            val item = result?.getOrNull()?.item ?: item
            // ⚠️ NO TOOLBAR TITLE HERE SINCE 2026-09-07, DELIBERATELY. This line read
            // `binding.toolBar.title = item.title.trim()`. The title now lives in the header content
            // (MediaHeaderAdapter.Success.bind sets it from the SAME source, state.item.title.trim()), and
            // having both on screen at rest was redundant — during the reverted overlap build they also
            // rendered overlapping mid-scroll.
            // CHECKED BEFORE REMOVING, so this is not an accessibility regression:
            //   • NOTHING READS IT BACK. `toolBar.title` was write-only across the whole app.
            //   • IT IS NOT AN ACTION BAR TITLE. There is no setSupportActionBar call anywhere in this
            //     app, so this MaterialToolbar is a plain view — its title was never announced on screen
            //     entry, only when focused. No accessibilityPaneTitle or announceForAccessibility exists
            //     anywhere either, so nothing else depended on it.
            //   • THE TEXT IS STILL THERE FOR A SCREEN READER. The header's @id/title is a real TextView,
            //     and ellipsize="end" + maxLines="2" truncate only visually — TalkBack reads the full
            //     string. A contentDescription on the toolbar would duplicate it, not replace it.
            // KNOWN COST: once the header scrolls past, the bar is empty and the page has no persistent
            // label. That is what the old collapsing header prevented. A scroll-driven HANDOFF (toolbar
            // title fades in only once the header title has passed) is the fix if that reads badly — it
            // does NOT need the reverted overlap; see the note at MediaDetailsFragment's removed
            // setupToolbarFade for why the previous attempt's arithmetic cannot simply be restored.
            // The cover load and the artist 240dp/circle cap moved to MediaHeaderAdapter.Success.bind on
            // 2026-09-05 — the cover is item 0 now, and a ViewHolder is reused, so both must run per bind
            // rather than once per result as they did here.
            val gradientScope = viewLifecycleOwner.lifecycleScope
            item.background.loadWithThumb(view) { gradientScope.launch { applyGradient(view, it) } }
        }
        parentFragmentManager.setFragmentResultListener("reload", this) { _, data ->
            if (data.getString("id") == item.id) viewModel.refreshTracks()
        }
        parentFragmentManager.setFragmentResultListener("delete", this) { _, data ->
            val playlist = item as? Playlist ?: return@setFragmentResultListener
            DeletePlaylistBottomSheet.show(
                requireActivity(), extensionId, playlist, !viewModel.isRefreshing
            )
        }
        parentFragmentManager.setFragmentResultListener("deleted", this) { _, data ->
            if (data.getString("id") == item.id) parentFragmentManager.popBackStack()
        }
    }
}