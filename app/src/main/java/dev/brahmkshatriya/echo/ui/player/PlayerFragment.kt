@file:Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")

package dev.brahmkshatriya.echo.ui.player

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.KeyEvent
import android.graphics.Outline
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import android.util.Log
import androidx.lifecycle.withResumed
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
import androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.slider.Slider
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.databinding.FragmentPlayerBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.background
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLiked
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.showBackground
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.unloadedCover
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyHorizontalInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.isFinalState
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupPlayerMoreBehavior
import dev.brahmkshatriya.echo.ui.media.MediaFragment
import dev.brahmkshatriya.echo.ui.media.more.MediaMoreBottomSheet
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.getColorsFrom
import dev.brahmkshatriya.echo.ui.player.PlayerTrackAdapter.Companion.configureClicking
import dev.brahmkshatriya.echo.ui.player.quality.FormatUtils.getDetailsFormatFirst
import dev.brahmkshatriya.echo.ui.player.quality.QualitySelectionBottomSheet
import dev.brahmkshatriya.echo.utils.ContextUtils.emit
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils
import dev.brahmkshatriya.echo.utils.image.ImageUtils.getCachedDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadAsCircle
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadBlurred
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.image.ImageUtils.warmMemoryCache
import dev.brahmkshatriya.echo.utils.ui.AnimationUtils.animateVisibility
import dev.brahmkshatriya.echo.utils.ui.AutoClearedValue.Companion.autoClearedNullable
import dev.brahmkshatriya.echo.utils.ui.CheckBoxListener
import dev.brahmkshatriya.echo.utils.ui.SimpleItemSpan
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.hideSystemUi
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isLandscape
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.UiUtils.marquee
import dev.brahmkshatriya.echo.utils.ui.UiUtils.toTimeString
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.registerOnUserPageChangeCallback
import dev.brahmkshatriya.echo.utils.ui.ViewPager2Utils.supportBottomSheetBehavior
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PlayerFragment : Fragment() {
    private var binding by autoClearedNullable<FragmentPlayerBinding>()
    private val viewModel by activityViewModel<PlayerViewModel>()
    private val uiViewModel by activityViewModel<UiViewModel>()
    private val adapter by lazy {
        PlayerTrackAdapter(uiViewModel, viewModel.playerState.current, adapterListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding!!
        binding.viewPager.supportBottomSheetBehavior()
        setupPlayerMoreBehavior(uiViewModel, binding.playerMoreContainer)
        configureOutline(binding.root)
        configureCollapsing(binding)
        configureColors()
        configurePlayerControls()
        configureBackgroundPlayerView()
    }

    // Wave motion follows the SAME discipline as the Ken Burns background below: a lifecycle pair plus the
    // playerSheetState observer, with a third condition (isPlaying) that Ken Burns does not have.
    // The "on" values are captured from the inflated view rather than duplicated as constants here, so the
    // style stays the single source of truth for amplitude and speed.
    private var waveSpeedPx = -1
    private var waveAmplitudePx = -1
    // NOT Fragment.isResumed: that reports mState and its value during onPause is an ordering detail
    // of FragmentStateManager. This is our own flag, set explicitly either side.
    private var waveResumed = false

    // Read the gating note in styles.xml (EchoLinearProgressIndicator.Wavy) before changing this.
    // Short version: the phase animator is never cancelled and does not need to be. setWaveSpeed(0)
    // stops every invalidation, and setWaveAmplitude(0) trips the hasWavyEffect gate so flattening on
    // pause IS the stop. Do not "fix" this by reaching for the animator.
    private fun updateWaveMotion() {
        val wave = binding?.playerControls?.seekWaveBar ?: return
        if (waveSpeedPx < 0) {
            waveSpeedPx = wave.waveSpeed
            waveAmplitudePx = wave.waveAmplitude
        }
        // playWhenReady, NOT Current.isPlaying. isPlaying is `player.isPlaying && state == READY`, and
        // Media3's own isPlaying additionally requires playbackSuppressionReason == NONE — so it goes
        // FALSE on buffering, on any seek that rebuffers, and on every track transition. Gating on it
        // flattened the wave on all of those and left it flat until an unrelated event happened to run
        // this again. playWhenReady tracks the player's INTENT and only changes on a real pause.
        // MainActivity:109 picked the same signal for keepScreenOn, with the same reasoning.
        val playing = viewModel.playWhenReady.value
        val expanded = uiViewModel.playerSheetState.value == STATE_EXPANDED
        // Amplitude tracks PLAYING only: a paused player shows a flat line, which is what the system
        // media notification does. Speed additionally requires the wave to be on screen and the fragment
        // resumed — collapsed is the one state the library does not handle for us, because the sheet's
        // views stay attached and window-visible when it slides down.
        wave.waveAmplitude = if (playing) waveAmplitudePx else 0
        wave.waveSpeed = if (playing && expanded && waveResumed) waveSpeedPx else 0
    }

    override fun onPause() {
        super.onPause()
        waveResumed = false
        updateWaveMotion()
        binding?.bgImage?.pause()
    }

    override fun onResume() {
        super.onResume()
        waveResumed = true
        updateWaveMotion()
        // TRACE (2026-08-29, temporary, GladixArt). One line per wake for the VISIBLE page only, recording
        // which of the three outcomes the cover took: no request and which guard declined, or a request
        // and where its bytes came from. Posted so it runs AFTER the wake traversal, i.e. after bind and
        // retryLoad have already decided - this REPORTS, it does not act. It issues nothing and writes no
        // page position, so it is not a re-run of the resume-time re-commits (aecc6700, reverted 3 Aug).
        // Needed because "the holder declined to issue" is the EXPECTED failure mode for the memory-cache
        // warm, not an edge case, so a negative result is otherwise unreadable. REMOVE WITH THE TRACE.
        binding?.let { b ->
            // Synchronous, and BEFORE the post: onResume runs ahead of the wake traversal, so this opens
            // the generation that bind/retryLoad then decide within, and the posted read below reports it.
            adapter.beginCoverTrace()
            b.viewPager.post {
                val pos = b.viewPager.currentItem
                val trace = adapter.coverTrace(pos)
                val boundId = trace?.second
                val curId = viewModel.playerState.current.value?.mediaItem?.mediaId
                // bound vs cur is the question this line exists to settle, so BOTH are printed and the
                // comparison is made here rather than left to be inferred from the page number. A mismatch
                // means the pager is a page behind and the stale cover is a symptom of that, not of any
                // image load; a match with a NETWORK decision means the cache was not consulted or the key
                // did not agree. warm=issued/done separates never-ran / parked / completed - see the
                // counters in ImageUtils.
                Log.d(
                    "GladixArt",
                    "wake: page=$pos bound=$boundId cur=$curId match=${boundId == curId} " +
                        "decision=${trace?.first ?: "no-holder"} " +
                        "warm=${ImageUtils.warmIssued.get()}/${ImageUtils.warmDone.get()}"
                )
            }
        }
        if (uiViewModel.playerSheetState.value == STATE_EXPANDED)
            binding?.bgImage?.resume()
    }

    private val collapseHeight by lazy {
        resources.getDimension(R.dimen.collapsed_cover_size).toInt()
    }

    private fun configureOutline(view: View) {
        val padding = 8.dpToPx(requireContext())
        var currHeight = collapseHeight
        var currRound = padding.toFloat()
        var currRight = 0
        var currLeft = 0
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    currLeft, 0, currRight, currHeight, currRound
                )
            }
        }
        view.clipToOutline = true

        var leftPadding = 0
        var rightPadding = 0

        val maxElevation = 4.dpToPx(requireContext()).toFloat()
        fun updateOutline() {
            val offset = max(0f, uiViewModel.playerSheetOffset.value)
            val inv = 1 - offset
            view.elevation = maxElevation * inv
            currHeight = collapseHeight + ((view.height - collapseHeight) * offset).toInt()
            // Full-width collapsed mini-bar minus the 8dp card inset, but still respecting the
            // start/end insets — flush to the screen edge in portrait, and flush to the nav-rail's
            // right edge in landscape (combined.start carries the rail width). Corner radius
            // (currRound) is intentionally left untouched.
            currLeft = (leftPadding * inv).toInt()
            currRight = view.width - (rightPadding * inv).toInt()
            currRound = max(padding * inv, padding * uiViewModel.playerBackProgress.value * 2)
            view.invalidateOutline()
        }
        observe(uiViewModel.combined) {
            leftPadding = if (view.context.isRTL()) it.end else it.start
            rightPadding = if (view.context.isRTL()) it.start else it.end
            updateOutline()
        }
        observe(uiViewModel.playerBackProgress) { updateOutline() }
        observe(uiViewModel.playerSheetOffset) { updateOutline() }
        view.doOnLayout { updateOutline() }
    }

    private fun configureCollapsing(binding: FragmentPlayerBinding) {
        binding.playerCollapsedContainer.root.clipToOutline = true

        val collapsedTopPadding = 8.dpToPx(requireContext())
        var currRound = collapsedTopPadding.toFloat()
        var currTop = 0
        var currBottom = collapseHeight
        var currRight = 0
        var currLeft = 0

        val view = binding.viewPager
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    currLeft, currTop, currRight, currBottom, currRound
                )
            }
        }
        view.clipToOutline = true

        val extraEndPadding = 108.dpToPx(requireContext())
        var leftPadding = 0
        var rightPadding = 0
        val isLandscape = requireContext().isLandscape()
        fun updateCollapsed() {
            // The `playerSheetOffset >= 1f` conjunct is the TWIN of the one in
            // PlayerTrackAdapter.updateCollapsed - see the long note there for the full reasoning.
            // Short version: playerSheetState is a SETTLED-STATES-ONLY signal (UiViewModel gates its write
            // with `if (!isFinalState(newState)) return`), so it still reads EXPANDED for the whole of a
            // collapse drag. Keying on state alone sent every frame into the EXPANDED arm, where `offset`
            // comes from moreSheetOffset - 0 with Up Next closed - so alphaInv stayed 1 and the expanded
            // header ("Playing From", the context title, the extension icon) slid down with the sheet at
            // FULL OPACITY instead of fading out, while the cover morphed correctly.
            // ⚠️ THERE ARE TWO updateCollapsed() FUNCTIONS. The adapter's animates the per-page cover; this
            // one animates the real collapsed bar, bgCollapsed, the toolbar and playerControls. Fixing one
            // fixes half the morph - that is exactly what happened on 2026-08-26, and why the cover
            // morphed while the header did not. Change them together.
            val (collapsedY, offset, collapsedOffset) = uiViewModel.run {
                if (playerSheetState.value == STATE_EXPANDED && playerSheetOffset.value >= 1f) {
                    val offset = moreSheetOffset.value
                    Triple(systemInsets.value.top, offset, if (isLandscape) 0f else offset)
                } else {
                    val offset = 1 - max(0f, playerSheetOffset.value)
                    Triple(-collapsedTopPadding, offset, offset)
                }
            }
            val collapsedInv = 1 - collapsedOffset
            binding.playerCollapsedContainer.root.run {
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                alpha = collapsedOffset * 2
                translationZ = -1f * collapsedInv
            }
            binding.bgCollapsed.run {
                translationY = collapsedY - collapseHeight * collapsedInv * 2
                alpha = min(1f, collapsedOffset * 2) - 0.5f
            }
            val alphaInv = 1 - min(1f, offset * 3)
            binding.expandedToolbar.run {
                translationY = collapseHeight * offset * 2
                alpha = alphaInv
                isVisible = offset < 1
                translationZ = -1f * offset
            }
            binding.playerControls.root.run {
                translationY = collapseHeight * offset * 2
                alpha = alphaInv
                isVisible = offset < 1
            }
            currTop = uiViewModel.run {
                val top = if (playerSheetState.value != STATE_EXPANDED) 0
                else collapsedTopPadding + systemInsets.value.top
                (top * max(0f, (collapsedOffset - 0.75f) * 4)).toInt()
            }
            val bot = currTop + collapseHeight
            currBottom = bot + ((view.height - bot) * collapsedInv).toInt()
            currLeft = (leftPadding * collapsedOffset).toInt()
            currRight = view.width - (rightPadding * collapsedOffset).toInt()
            currRound = collapsedTopPadding * collapsedOffset
            view.invalidateOutline()
        }

        view.doOnLayout { updateCollapsed() }
        observe(uiViewModel.combined) {
            val system = uiViewModel.systemInsets.value
            binding.constraintLayout.applyInsets(system, 64, 0)
            binding.expandedToolbar.applyInsets(system)
            val insets = uiViewModel.run {
                if (playerSheetState.value == STATE_EXPANDED) system
                else getCombined()
            }
            // Collapsed mini-player always uses getCombined() (rail included), NOT the STATE_EXPANDED-
            // gated `insets`: on rotate-while-expanded → collapse, `combined` last emits while EXPANDED
            // (gate picks rail-less `system`) and collapsing never re-emits it, so the bar kept a zero
            // rail inset and overlapped the rail. The container is alpha=0 whenever landscape+expanded
            // (updateCollapsed line ~222), so carrying the rail inset while expanded is inert. The gate
            // stays for playerControls below (line 273), which needs `system` for its expanded end-inset.
            binding.playerCollapsedContainer.root.applyHorizontalInsets(uiViewModel.getCombined())
            binding.playerControls.root.applyHorizontalInsets(
                insets,
                requireActivity().isLandscape()
            )
            val left = if (requireContext().isRTL()) system.end + extraEndPadding else system.start
            leftPadding = collapsedTopPadding + left
            val right = if (requireContext().isRTL()) system.start else system.end + extraEndPadding
            rightPadding = collapsedTopPadding + right
            // Landscape/rail: after rotation the viewPager cover isn't settled to its landscape
            // geometry when this fires, so a synchronous updateCollapsed() would read stale
            // cover.left/height and land the morph wrong (art/title overlap). Defer to the next
            // layout so it reads settled geometry. (Gate is isLandscape — NOT it.bottom, because
            // here `it` is uiViewModel.combined, whose bottom carries playerInsets and is never 0
            // in landscape.) Portrait keeps the synchronous path unchanged.
            if (isLandscape) {
                binding.viewPager.doOnNextLayout { updateCollapsed(); adapter.insetsUpdated() }
            } else {
                updateCollapsed()
                adapter.insetsUpdated()
            }
        }

        observe(uiViewModel.moreSheetOffset) {
            updateCollapsed()
            adapter.moreOffsetUpdated()
        }
        observe(uiViewModel.playerSheetOffset) {
            updateCollapsed()
            adapter.playerOffsetUpdated()

            viewModel.browser.value?.volume = 1 + min(0f, it)
            if (it < 1)
                requireActivity().hideSystemUi(false)
            else if (uiViewModel.playerBgVisible.value)
                requireActivity().hideSystemUi(true)
        }

        observe(uiViewModel.playerSheetState) {
            updateCollapsed()
            if (isFinalState(it)) adapter.playerSheetStateUpdated()
            if (it == STATE_COLLAPSED) emit(uiViewModel.playerBgVisible, false)
            when (it) {
                STATE_EXPANDED -> binding.bgImage.resume()
                else -> binding.bgImage.pause()
            }
            updateWaveMotion()
            // Canvas/video is fullscreen-only — re-run applyPlayer() for the new sheet state: on collapse it
            // DETACHES the video surface (playerView.player = null) so the Canvas/video stops rendering in the
            // mini-bar (surface-only — audio keeps playing via the service player); on expand it re-attaches
            // and shows it. Same transition as the KenBurns pause above. playerSheetState only emits final
            // states (HIDDEN/COLLAPSED/EXPANDED), so this fires once per settle — no mid-drag churn.
            applyPlayer()
        }
        binding.playerControls.root.doOnLayout {
            uiViewModel.playerControlsHeight.value = it.height
            adapter.playerControlsHeightUpdated()
        }
        var bgBackCallback: OnBackPressedCallback? = null
        observe(uiViewModel.playerBgVisible) { visible ->
            binding.viewPager.isUserInputEnabled = !visible
            binding.fgContainer.animateVisibility(!visible)
            binding.playerMoreContainer.animateVisibility(!visible)
            bgBackCallback?.remove()
            bgBackCallback = null
            if (visible) {
                bgBackCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        uiViewModel.changeBgVisible(false)
                    }
                }.also {
                    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it)
                }
            }
        }
        binding.bgPanel.configureClicking(adapterListener, uiViewModel)
        binding.expandedToolbar.setNavigationOnClickListener {
            uiViewModel.collapsePlayer()
        }
    }

    private val adapterListener = object : PlayerTrackAdapter.Listener {
        override fun onClick(): Unit = uiViewModel.run {
            if (playerSheetState.value != STATE_EXPANDED) changePlayerState(STATE_EXPANDED)
            else {
                if (moreSheetState.value == STATE_EXPANDED) {
                    changeMoreState(STATE_COLLAPSED)
                    return
                }
                val shouldBeVisible = !playerBgVisible.value
                if (shouldBeVisible) {
                    val binding = binding ?: return@run
                    if (binding.bgImage.drawable == null && !binding.playerView.player.hasVideo())
                        return
                    changeMoreState(STATE_COLLAPSED)
                }
                changeBgVisible(shouldBeVisible)
            }
        }

        override fun onStartDoubleClick() {
            viewModel.seekToAdd(-10000)
        }

        override fun onEndDoubleClick() {
            viewModel.seekToAdd(10000)
        }
    }

    private var isInitialLoad = true
    private var pendingPageScroll: Runnable? = null
    private fun configurePlayerControls() {
        val viewPager = binding!!.viewPager
        viewPager.adapter = adapter
        (viewPager.getChildAt(0) as? RecyclerView)?.itemAnimator = null
        viewPager.registerOnUserPageChangeCallback { pos, isUser ->
            val curr = viewModel.playerState.current.value
            val index = curr?.let { c -> viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId } } ?: -1
            if (index != pos && isUser) viewModel.seek(pos)
        }

        fun submit() {
            val capturedCurrent = viewModel.playerState.current.value
            // `submitted` is the EXACT instance handed to submitList, so the index computed below and
            // the list the adapter receives provably come from one generation. Do not re-read
            // viewModel.queue inside the callback: it can advance a generation (emitFullQueue writes 50ms
            // after a timeline change) while the adapter still holds this one, and an index from one
            // generation applied to another is off by however many tracks were removed in between.
            val submitted = viewModel.queue
            val capturedIndex = capturedCurrent?.let { c ->
                submitted.indexOfFirst { it.mediaId == c.mediaItem.mediaId }.takeIf { it != -1 }
            }
            adapter.submitList(submitted) {
                val index = capturedIndex ?: return@submitList
                val viewPager = binding?.viewPager ?: return@submitList
                val current = viewPager.currentItem
                // Only smooth-scroll when the display is actually on. A smooth scroll is driven by
                // Choreographer frames, which are paused while the screen is off — so a screen-off auto-advance's
                // smoothScrollToPosition stalls and desyncs ViewPager2's logical mCurrentItem from the rendered
                // page (the one-behind bug). A NON-smooth setCurrentItem commits via the LayoutManager's pending
                // scroll (scrollToPosition when laid out, mPendingCurrentItem when not), applied on the next
                // layout pass at screen-on — no frames needed — so the correct page renders with no stale frame.
                // On-screen advances keep the animated ±1 behavior.
                // Gate on the DISPLAY, not on lifecycle state, because the question is "will Choreographer
                // deliver frames" and the lifecycle only answers "is this Fragment started".
                //
                // ⚠️ This comment previously claimed the gate was needed because a NATURAL DISPLAY TIMEOUT
                // leaves the Activity reporting STARTED while the display stops producing frames, and that
                // a power-button press collapses that window. MEASURED 2026-08-24 and it is FALSE: over
                // eight consecutive screen-off auto-advances the lifecycle read CREATED every time — the
                // Activity is genuinely stopped on a natural timeout, so a STARTED check would have
                // returned the same answer the display check does. The gate is not wrong, but it was
                // justified by a mechanism that does not exist; do not cite that mechanism again.
                //
                // What the display check still buys is directness and the DOZE window: Display.STATE_ON is
                // the signal actually being asked about, and DOZE / DOZE_SUSPEND / OFF all correctly read as
                // "no frames", whereas lifecycle state only correlates. DisplayManager rather than
                // Context.getDisplay, which is API 30+ and we ship 24.
                // Asymmetry that justifies erring conservative: smooth=false is ALWAYS correct — the
                // non-smooth setCurrentItem commits via the LayoutManager's pending scroll and needs no
                // frames — so a false negative costs an animation, while a false positive leaves the page
                // stale until the user interacts. This flag is the ONLY thing changed; the writers of the
                // page position are untouched, which is what the reverted 2026-07-30 pre-draw re-commit
                // got wrong (it added a third writer and produced a permanent one-behind).
                val displayOn = requireContext().getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
                val smooth = displayOn && !isInitialLoad && abs(index - current) <= 1
                isInitialLoad = false
                if (!viewPager.isLaidOut) viewPager.setCurrentItem(index, smooth)
                else {
                    pendingPageScroll?.let { viewPager.removeCallbacks(it) }
                    val runnable = Runnable {
                        val liveCurrent = viewModel.playerState.current.value
                        val liveIndex = liveCurrent?.let { c ->
                            viewModel.queue.indexOfFirst { it.mediaId == c.mediaItem.mediaId }.takeIf { it != -1 }
                        } ?: index
                        // ⚠️ KNOWN HAZARD, left in place deliberately (measured 2026-08-24, never observed
                        // to fire). `index` is consistent with `submitted`; `liveIndex` is re-derived here
                        // from viewModel.queue at POST time — a potentially LATER generation — and then
                        // applied to the adapter's EARLIER list. viewModel.queue advances 50ms after a
                        // timeline change (emitFullQueue) while the adapter is only re-submitted by
                        // submit(), so the two can disagree by however many tracks were removed in between.
                        // Instrumented over eight consecutive screen-off advances: the two always agreed,
                        // because submit() runs from the ungated `current` collector before the 50ms write
                        // lands. This is the SAME false step as the reverted 2026-07-30 pre-draw re-commit
                        // (60ab8d0c), which derived an index from the live queue and applied it to a stale
                        // adapter list and produced a permanent one-behind. Do not add a third derivation
                        // here; if this ever needs touching, take `index` and delete the re-derivation.
                        binding?.viewPager?.setCurrentItem(liveIndex, smooth)
                    }
                    pendingPageScroll = runnable
                    viewPager.post(runnable)
                }
            }
        }

        val binding = binding!!
        binding.playerControls.trackHeart.addOnCheckedStateChangedListener(likeListener)
        // Deliberately not using observe()/flowWithLifecycle here: that restarts collection
        // (and redelivers the StateFlow's current value) on every STARTED re-entry, which can
        // fire multiple times in quick succession during Activity recreation. This must collect
        // exactly once per Fragment instance since it drives non-idempotent side effects
        // (image load/dispose, page scroll).
        // PHONE-ONLY sheet-state driver. This is the SOLE sheet show/hide logic on phone (BottomSheet +
        // PlayerFragment). MainActivity's current-observer does NOT participate here — it is TV-only, gated by
        // R.id.tvMiniPlayer (see MainActivity.setupTvMiniPlayer). TV uses PlayerTvFragment + tvMiniPlayer;
        // Android Auto has no Fragment at all. So changes in this block affect phone only.
        // Verified 2026-08-23, with commit references so this is checkable rather than folklore:
        // setupTvMiniPlayer() early-returns on `R.id.tvMiniPlayer ?: return` (that id exists only in
        // layout-land-television), and it has done so since the method was created in 75501299
        // (2026-05-26). The RESUMED-gated STATE_COLLAPSED transition inside it was added LATER, in
        // d4c3b9bb (2026-06-08), whose session notes describe it as a PHONE cold-start fix ("blank Now
        // Playing bar") — but it went into the already-TV-gated method, so it has never executed on a
        // phone. Do not reason about phone sheet state from that guard. Approaching from the phone side
        // and assuming otherwise cost real debugging time on 2026-08-23; the mirror of this note, written
        // after the same mistake from the TV side, is at MainActivity:242-249.
        lifecycleScope.launch {
            viewModel.playerState.current.collectLatest {
                uiViewModel.run {
                    // Persistent transport bar (Spotify / YouTube Music / Apple Music model): the mini bar is
                    // shown whenever there is a current track and hidden ONLY when the queue empties. There is
                    // no dismiss gesture — the sheet is non-hideable while shown (applyPlayerBehaviorState).
                    // This is a pure current-STATE rule, not an edge: the first non-null emission shows
                    // COLLAPSED, so a cold-start restore (current is set before this Fragment subscribes) needs
                    // no prior null and no dependence on when the sheet settles. playerSheetState is read only
                    // to preserve a user's EXPANDED and to avoid churn when the bar is already shown.
                    if (it == null) changePlayerState(STATE_HIDDEN)
                    else if (playerSheetState.value == STATE_HIDDEN) changePlayerState(STATE_COLLAPSED)
                }
                submit()
                it?.mediaItem ?: return@collectLatest
                binding.applyCurrent(it.mediaItem)
                loadCurrentBackground(it.mediaItem)
                // ═══ THIRD ATTEMPT ON THE SCREEN-OFF STALE COVER BUG. Read before changing. ═══
                // The two before it are REFUTED, both shipped in f2b661b0 (build 1057) and both live in
                // 1058 and 1059 while the bug survived:
                //   the paint guard (a superseded load must not paint) - so it is NOT a stale delivery
                //     overwriting a correct one. The guard stays because it is correct anyway;
                //   a resume-time unconditional re-issue - so re-issuing at ON_START does not cure it.
                //     Removed entirely rather than narrowed; it had a failed experiment behind it.
                //
                // WHAT THIS TESTS, and it is ONE property: a request that is ALREADY AT THE LIFECYCLE GATE
                // when the gate opens. Nothing executes while the screen is off - every enqueued load
                // awaitStarted()s on the Activity lifecycle (ImageUtils:140), the art_probe included, so
                // "issued in the dark" was never the distinguishing trait and an earlier reading of mine
                // that said so was wrong. What the probe had was a request ENQUEUED per dark advance, so at
                // ON_START one already existed for the current track; the recorded consequence was bind
                // resolving src=MEMORY_CACHE in ~2ms at a healthy wake. The ViewHolder enqueues NOTHING
                // while dark: its only two entry points are bind (onBindViewHolder) and retryLoad
                // (onViewAttachedToWindow), both driven by a layout traversal that a STOPPED Activity never
                // performs - and at wake both are guarded and may decline to issue at all.
                //
                // TWO EXISTENCE PROOFS, and what they share. The mini bar's cover (applyCurrent, :934) has
                // never had this bug, and neither did art_probe. Both load from THIS collector by identity
                // into a non-recycled view; both use loadWithThumb's lambda target, so NEITHER gets
                // requestManager coalescing - which rules coalescing out as the discriminator. The property
                // they share with each other and not with the ViewHolder is issuance from `current`.
                //
                // CLEAR OF THE RESUME-TIME TRAP. Two prior resume-time re-commits made things worse, a
                // permanent one-behind rather than an intermittent one (Jul 28/30 doOnPreDraw, aecc6700,
                // reverted 3 Aug by 25fae92a). This is not one: it writes no page position, touches no
                // ViewPager state, and is not a resume-time action - it runs when `current` changes.
                //
                // COST is one decode per advance into a ~38MB memory cache. Deliberately not engineered
                // around: the 1032/1039 OOMs were the service-restart loop, not bitmaps, and toMaxRes's
                // 1920x1920 rewrite is TV only.
                //
                // ⚠️ IF THIS FAILS, the next suspect is NOT the cache. It is that the holder declines to
                // issue at wake - retryLoad returns early on `coverDrawable != null`, bind on
                // `item?.mediaId == lastBoundMediaId` - in which case a warm cache is never consulted and
                // this refutes for a reason unrelated to warming.
                context?.let { ctx -> it.mediaItem.track.cover.warmMemoryCache(ctx) }
            }
        }

        // The wave's primary driver, and deliberately a GATED observer rather than a raw launch:
        // ContextUtils.observe is flowWithLifecycle(STARTED), so it re-subscribes on every ON_START and a
        // StateFlow replays its current value. That makes this LEVEL-driven — a missed edge self-corrects
        // at the next wake instead of leaving the wave wrong indefinitely, which is what the previous
        // edge-only wiring off the `current` collector did.
        observe(viewModel.playWhenReady) { updateWaveMotion() }

        observe(viewModel.queueFlow) { submit() }
        observe(viewModel.browser) { controller ->
            if (controller != null && viewModel.queue.isNotEmpty() && adapter.currentList.isEmpty()) {
                submit()
            }
        }

        val playPauseListener = CheckBoxListener { viewModel.setPlaying(it) }
        binding.playerControls.trackPlayPause
            .addOnCheckedStateChangedListener(playPauseListener)
        binding.playerCollapsedContainer.collapsedTrackPlayPause
            .addOnCheckedStateChangedListener(playPauseListener)
        observe(viewModel.playWhenReady) {
            binding.run {
                playPauseListener.enabled = false
                playerControls.trackPlayPause.isChecked = it
                playerCollapsedContainer.collapsedTrackPlayPause.isChecked = it
                playPauseListener.enabled = true

                val isBuffering = viewModel.buffering.value && it
                playerControls.playingIndicator.alpha = if (isBuffering) 1f else 0f
                playerCollapsedContainer.collapsedPlayingIndicator.alpha = if (isBuffering) 1f else 0f
            }
        }
        observe(viewModel.buffering) {
            val playWhenReady = viewModel.playWhenReady.value
            val isBuffering = it && playWhenReady
            binding.playerControls.playingIndicator.alpha = if (isBuffering) 1f else 0f
            binding.playerCollapsedContainer.collapsedPlayingIndicator.alpha = if (isBuffering) 1f else 0f
        }

        observe(viewModel.progress) { (curr, buff) ->
            binding.playerCollapsedContainer.run {
                collapsedBuffer.progress = buff.toInt()
                collapsedSeekbar.progress = curr.toInt()
            }
            binding.playerControls.run {
                if (!seekBar.isPressed) {
                    bufferBar.progress = buff.toInt()
                    seekWaveBar.progress = curr.toInt()
                    seekBar.value = max(0f, min(curr.toFloat(), seekBar.valueTo))
                    trackCurrentTime.text = curr.toTimeString()
                }
            }
        }

        // Duration comes from a COMBINE of totalDuration + current, not totalDuration alone. On cold start
        // player.duration is TIME_UNSET (unprepared) so totalDuration stays null, and its null->null is
        // conflated to no emission — but the restored track carries a known duration. combine re-fires when
        // current arrives, so the `?: current.track.duration` fallback actually evaluates instead of being
        // stranded behind a totalDuration emission that never comes. Precedence stays totalDuration-first.
        // DELIBERATE MIRROR of PlayerTvFragment's duration observer — keep the two in sync; each writes its
        // own views (phone: playerControls + collapsed bar; TV: tvSeekBar/tvTotalTime/tvBufferBar).
        observe(combine(viewModel.totalDuration, viewModel.playerState.current) { total, current ->
            total ?: current?.track?.duration ?: 0L
        }) { duration ->
            binding.playerCollapsedContainer.run {
                collapsedSeekbar.max = duration.toInt()
                collapsedBuffer.max = duration.toInt()
            }
            binding.playerControls.run {
                bufferBar.max = duration.toInt()
                seekWaveBar.max = duration.toInt()
                seekBar.apply {
                    value = max(0f, min(value, duration.toFloat()))
                    valueTo = 1f + duration
                }
                trackTotalTime.text = duration.toTimeString()
            }
        }


        val repeatModes = listOf(REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE)
        val animatedVectorDrawables = requireContext().run {
            fun asAnimated(id: Int) =
                AppCompatResources.getDrawable(this, id) as AnimatedVectorDrawable
            listOf(
                asAnimated(R.drawable.ic_repeat_one_to_repeat_off_40dp),
                asAnimated(R.drawable.ic_repeat_off_to_repeat_40dp),
                asAnimated(R.drawable.ic_repeat_to_repeat_one_40dp)
            )
        }
        val drawables = requireContext().run {
            fun asDrawable(id: Int) = AppCompatResources.getDrawable(this, id)!!
            listOf(
                asDrawable(R.drawable.ic_repeat_off_40dp),
                asDrawable(R.drawable.ic_repeat_40dp),
                asDrawable(R.drawable.ic_repeat_one_40dp),
            )
        }

        binding.playerControls.trackRepeat.icon =
            drawables[repeatModes.indexOf(viewModel.repeatMode.value)]

        fun changeRepeatDrawable(repeatMode: Int) = binding.playerControls.trackRepeat.run {
            val index = repeatModes.indexOf(repeatMode)
            icon = animatedVectorDrawables[index]
            (icon as Animatable).start()
        }

        binding.playerControls.run {
            seekBar.apply {
                addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        trackCurrentTime.text = value.toLong().toTimeString()
                        // The wave is a separate view from the Slider, so it is NOT carried along by the
                        // drag. The progress observer above is gated on !seekBar.isPressed, so during a
                        // gesture nothing else updates it and the wave would visibly lag the thumb for the
                        // whole drag. Drive it from here so the two stay together; fromUser keeps this off
                        // the programmatic path, which the observer already owns.
                        seekWaveBar.progress = value.toInt()
                    }
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) =
                        viewModel.seekTo(slider.value.toLong())
                })
                val uiModeManager =
                    requireContext().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                val isTV = requireContext().packageManager
                    .hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                if (isTV) {
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { viewModel.seekToAdd(-10_000); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { viewModel.seekToAdd(10_000); true }
                            else -> false
                        }
                    }
                }
            }

            trackNext.setOnClickListener {
                viewModel.next()
                (trackNext.icon as Animatable).start()
            }
            observe(viewModel.nextEnabled) { trackNext.isEnabled = it }

            trackPrevious.setOnClickListener {
                viewModel.previous()
                (trackPrevious.icon as Animatable).start()
            }
            observe(viewModel.previousEnabled) { trackPrevious.isEnabled = it }

            val shuffleListener = CheckBoxListener { viewModel.setShuffle(it) }
            trackShuffle.addOnCheckedStateChangedListener(shuffleListener)
            observe(viewModel.shuffleMode) {
                shuffleListener.enabled = false
                trackShuffle.isChecked = it
                shuffleListener.enabled = true
            }

            trackRepeat.setOnClickListener {
                val mode = when (viewModel.repeatMode.value) {
                    REPEAT_MODE_OFF -> REPEAT_MODE_ALL
                    REPEAT_MODE_ALL -> REPEAT_MODE_ONE
                    else -> REPEAT_MODE_OFF
                }
                changeRepeatDrawable(mode)
                viewModel.setRepeat(mode)
            }
            observe(viewModel.repeatMode) { changeRepeatDrawable(it) }

            trackSubtitle.setOnClickListener {
                QualitySelectionBottomSheet().show(parentFragmentManager, null)
            }
            observe(viewModel.serverAndTracks) { (tracks, server, index) ->
                // HIDE, DO NOT BLANK, and do not clear the text. Between an item transition and its
                // onTracksChanged `tracks` is null (the stamp does not match yet), and this window is the
                // full resolve time - 2.4-3.8s measured. Writing "Unknown quality" there would flash it on
                // every advance, which is worse than the stale value it replaced. Blanking is no better:
                // this view has a 40dp minHeight and a shape_pill background, so an empty string leaves an
                // empty capsule that reads as a failure. Absent reads as "not known yet", which is true.
                // The text is deliberately left in place while hidden so a re-show does not repaint.
                val details = tracks?.getDetailsFormatFirst(requireContext(), server, index)
                    ?.joinToString(" ⦿ ")?.takeIf { it.isNotBlank() }
                if (details != null) trackSubtitle.text = details
                trackSubtitle.isVisible = details != null
            }
        }
    }

    private val likeListener = CheckBoxListener { viewModel.likeCurrent(it) }

    // Ken Burns background is driven by CURRENT TRACK IDENTITY (loadCurrentBackground), like the mini bar —
    // NOT by the attached page's coverDrawable, which is null/detached after a screen-off auto-advance and
    // left it stale + downstream of the pager. Guarded by lastBlurredItemId so re-applying on every resume is
    // a no-op when the track is unchanged.
    private var lastBlurredItemId: String? = null
    private fun loadCurrentBackground(item: MediaItem?) {
        val bg = binding?.bgImage ?: return
        val context = context ?: return
        if (!context.showBackground()) {
            bg.setImageDrawable(null)
            lastBlurredItemId = null
            return
        }
        val itemId = item?.mediaId
        if (itemId == lastBlurredItemId) return
        lastBlurredItemId = itemId
        bg.loadBlurred(item?.track?.cover, 8f)
    }

    private fun configureColors() {
        observe(viewModel.playerState.current) { adapter.onCurrentUpdated() }
        var last: Drawable? = null
        // Colors/dynamic-theming still derive from the attached page drawable; only the Ken Burns background
        // was moved to identity-based loading (loadCurrentBackground).
        // Captured once at setup (onViewCreated, provably attached) rather than per-invocation. This
        // listener is NOT lifecycle-gated: PlayerTrackAdapter.applyDrawable() invokes it from the
        // ViewHolder's async cover-load callback, which can land after onDestroyView (activity recreation),
        // and requireContext() would throw there. isDynamic()/getColorsFrom() only need a Context, not an
        // attached one. Same fix as PlayerTvFragment.configureColors — the phone site has the identical
        // shape and simply hasn't been the one to crash yet.
        val listenerContext = requireContext()
        adapter.currentDrawableListener = { drawable ->
            if (last != drawable) {
                uiViewModel.playerDrawable.value = drawable
                val colors = if (listenerContext.isDynamic())
                    listenerContext.getColorsFrom(drawable?.toBitmap()) else null
                uiViewModel.playerColors.value = colors
                // After the work, for the same reason as PlayerTvFragment's lastDrawable: an early exit
                // must not leave the cache claiming this drawable was applied. Wholly synchronous here, so
                // moving it is free.
                last = drawable
            }
        }
        val bufferView =
            binding?.playerView?.findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)
        observe(uiViewModel.playerColors) {
            val context = requireContext()
            if (context.isPlayerColor() && context.isDynamic()) {
                val newAccent = it?.accent
                if (uiViewModel.lastPlayerAccentColor != newAccent) {
                    // See PlayerTvFragment.configureColors for the full reasoning: written only where the
                    // recreate actually runs, and inside the withResumed block so no cancellation point
                    // separates the recreate from the flag recording it. Pre-assigning left the deferred
                    // branch able to drop the recreate while claiming it happened — and since the accent is
                    // seeded into the theme only by MainActivity.applyUiChanges at activity creation, that
                    // stales the theme until an unrelated recreate, with no retry (the guard blocks it).
                    if (requireActivity().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        requireActivity().recreate()
                        uiViewModel.lastPlayerAccentColor = newAccent
                    } else {
                        lifecycleScope.launch {
                            lifecycle.withResumed {
                                requireActivity().recreate()
                                uiViewModel.lastPlayerAccentColor = newAccent
                            }
                        }
                    }
                    return@observe
                }
            }
            val colors = it ?: context.defaultPlayerColors()
            val binding = binding!!
            adapter.onColorsUpdated()

            binding.run {
                val color = if (requireContext().isDynamic()) colors.accent
                else colors.background
                root.setBackgroundColor(color)
                val backgroundState = ColorStateList.valueOf(colors.background)
                bgGradient.imageTintList = backgroundState
                bgCollapsed.backgroundTintList = backgroundState
                bufferView?.indeterminateDrawable?.setTint(colors.accent)
                expandedToolbar.run {
                    setTitleTextColor(colors.onBackground)
                    setSubtitleTextColor(colors.onBackground)
                }
            }

            binding.playerCollapsedContainer.run {
                collapsedPlayingIndicator.setIndicatorColor(colors.accent)
                collapsedSeekbar.setIndicatorColor(colors.accent)
                // collapsedBuffer.setIndicatorColor is DELIBERATELY ABSENT - do not restore it without
                // also changing the layout. collapsedBuffer's indicator is transparent in XML, and a
                // runtime setIndicatorColor here would override that and paint the buffer line straight
                // back. Only the rail is tinted now. Same treatment as bufferBar below and tvBufferBar in
                // PlayerTvFragment; keep all three in step.
                // BUFFERING IS NOW SHOWN NOWHERE IN THE APP - the full screen and TV players lost it in
                // 1055 for the wavy seek bar, and this was the last surface still drawing one. See the
                // note on collapsed_buffer in item_player_collapsed_controls.xml for the full reasoning
                // and for why this view still exists (it carries the rail, its 0.5 alpha and its zero
                // gap size, none of which can move to collapsed_seekbar).
                collapsedBuffer.trackColor = colors.onBackground
                collapsedTrackTitle.setTextColor(colors.onBackground)
                collapsedTrackArtist.setTextColor(colors.onBackground)
            }

            binding.playerControls.run {
                // The accent goes on the WAVE, not the Slider's active track. seekBar.trackColorActive is
                // transparent in XML so the wave is the only thing drawing the position line — but a
                // runtime tint would override that XML and paint a straight line back under the wave.
                seekWaveBar.setIndicatorColor(colors.accent)
                // The heart is the one control here that shows a persistent CHOICE, so it gets the
                // accent when checked and stays amoled_fg otherwise (see color/button_player_heart.xml
                // for why accent's weak-palette fallback is acceptable on a glyph but not on a fill).
                trackHeart.buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(colors.accent, colors.onBackground)
                )
                // Pill takes `background`, NOT accent: it sits between the two timestamps and must read
                // as a container behind them rather than competing with them.
                trackSubtitle.backgroundTintList = ColorStateList.valueOf(colors.background)
                trackSubtitle.setTextColor(colors.onBackground)
                seekBar.thumbTintList = ColorStateList.valueOf(colors.accent)
                playingIndicator.setIndicatorColor(colors.accent)
                // bufferBar.setIndicatorColor is DELIBERATELY ABSENT — do not restore it without also
                // changing the layout. bufferBar's indicator is transparent in XML because
                // DeterminateDrawable never assigns startFraction (it stays 0f), so the indicator can only
                // fill from the left edge and drew a solid accent line under the whole played portion,
                // visible through the wave. A runtime setIndicatorColor here would override that XML and
                // paint it straight back. Only the rail is tinted now. See the note on bufferBar in
                // item_player_controls.xml. PlayerTvFragment carries the same omission for tvBufferBar —
                // keep the two in step.
                bufferBar.trackColor = colors.onBackground
                trackCurrentTime.setTextColor(colors.onBackground)
                trackTotalTime.setTextColor(colors.onBackground)
                trackTitle.setTextColor(colors.onBackground)
                trackArtist.setTextColor(colors.onBackground)
            }
        }
    }

    private fun FragmentPlayerBinding.applyCurrent(item: MediaItem) {
        val track = item.track
        val extId = item.extensionId
        expandedToolbar.run {
            val itemContext = item.context
            title = if (itemContext != null) context.getString(R.string.playing_from) else null
            subtitle = itemContext?.title
            val navigableContext = when (itemContext) {
                is EchoMediaItem.Lists, is Artist -> itemContext
                else -> null
            }
            setOnClickListener(if (navigableContext != null) View.OnClickListener {
                openItem(extId, navigableContext)
            } else null)
        }
        // Overflow moved out of the toolbar menu (PlayerToolbarStyle no longer sets `menu`) and down
        // beside the heart, so it is a plain click listener now rather than setOnMenuItemClickListener.
        playerControls.trackMore.setOnClickListener { onMoreClicked(item) }
        // Playing extension's icon, top right. Resolved from the item's extensionId - the PLAYING
        // extension - not from extensionLoader.current, which is the BROWSING one and would swap the
        // icon mid-track when the user changes tabs. loadAsCircle keeps ic_extension_32dp when the
        // extension has none, so the slot never goes empty.
        lifecycleScope.launch {
            val icon = viewModel.getExtensionIcon(extId)
            icon.loadAsCircle(extensionIcon, R.drawable.ic_extension_32dp) {
                // Only overwrite on a real image: a null delivery must leave the XML fallback in place
                // rather than blanking the slot.
                if (it != null) extensionIcon.setImageDrawable(it)
            }
        }
        playerCollapsedContainer.run {
            collapsedTrackTitle.text = track.title
            collapsedTrackArtist.text = track.artists.joinToString(", ") { it.name }
            val thumb = collapsedTrackCover.drawable
                ?: item.unloadedCover?.getCachedDrawable(requireContext())
            track.cover.loadWithThumb(collapsedTrackCover, thumb) {
                val image = it
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.ic_music, context.theme)
                setImageDrawable(image)
            }
        }
        playerControls.run {
            trackTitle.text = track.title
            trackTitle.marquee()
            val artists = track.artists
            val artistNames = artists.joinToString(", ") { it.name }
            val span = SpannableString(artistNames)

            artists.forEach { artist ->
                val start = artistNames.indexOf(artist.name)
                val end = start + artist.name.length
                val clickableSpan = SimpleItemSpan(trackArtist.context) {
                    openItem(extId, artist)
                }
                runCatching {
                    span.setSpan(
                        clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            trackArtist.text = span
            trackArtist.movementMethod = LinkMovementMethod.getInstance()
            likeListener.enabled = false
            trackHeart.isChecked = item.isLiked
            likeListener.enabled = true
            lifecycleScope.launch {
                val isTrackClient = viewModel.isLikeClient(item.extensionId)
                trackHeart.isVisible = isTrackClient
            }
        }
    }

    private fun openItem(extension: String, item: EchoMediaItem) {
        requireActivity().openFragment<MediaFragment>(
            null, MediaFragment.getBundle(extension, item, false)
        )
    }

    private fun onMoreClicked(item: MediaItem) {
        MediaMoreBottomSheet.show(
            this, requireActivity().supportFragmentManager,
            R.id.navHostFragment, item.extensionId, item.track, item.isLoaded, true
        )
    }

    private fun Player?.hasVideo() =
        this?.currentTracks?.groups.orEmpty().any { it.type == C.TRACK_TYPE_VIDEO }

    private fun applyVideoVisibility(visible: Boolean) {
        binding?.playerView?.isVisible = visible
        binding?.bgImage?.isVisible = !visible
        if (requireContext().isLandscape()) return
        binding?.playerControls?.trackCoverPlaceHolder?.isVisible = visible
        adapter.updatePlayerVisibility(visible)
    }

    private var oldBg: Streamable.Media.Background? = null
    private var backgroundPlayer: Player? = null

    @OptIn(UnstableApi::class)
    private fun applyPlayer() {
        // Canvas/video is fullscreen-only. When NOT expanded (mini-bar / hidden), DETACH the video surface so
        // the Canvas/video stops rendering in the collapsed bar. `playerView.player = null` clears ONLY the
        // view's video surface (clearVideoSurface); the actual playback lives in the service player behind
        // the MediaController (mainPlayer), so AUDIO IS UNAFFECTED — it keeps playing while collapsed. The
        // static bg_image (blurred art) shows instead. Video/Canvas re-attaches on expand (below), rendering
        // at the live position. Setting isVisible alone did NOT stop the SurfaceView-backed PlayerView —
        // detaching the surface is the reliable stop (the true analog of KenBurns' animator pause).
        if (uiViewModel.playerSheetState.value != STATE_EXPANDED) {
            binding?.playerView?.player = null
            backgroundPlayer?.playWhenReady = false   // no-op for the main-player video path (null); pauses
                                                      // the silent Canvas loop in the background-streamable case
            binding?.playerView?.isVisible = false
            binding?.bgImage?.isVisible = true
            return
        }
        val mainPlayer = viewModel.browser.value
        val background = viewModel.playerState.current.value?.mediaItem?.background
        val visible = if (mainPlayer.hasVideo()) {
            binding?.playerView?.player = mainPlayer
            binding?.playerView?.resizeMode = RESIZE_MODE_FIT
            backgroundPlayer?.release()
            backgroundPlayer = null
            true
        } else if (background != null) {
            if (oldBg != background || backgroundPlayer == null) {
                oldBg = background
                backgroundPlayer?.release()
                backgroundPlayer = getPlayer(requireContext(), viewModel.cache, background)
            }
            binding?.playerView?.player = backgroundPlayer
            binding?.playerView?.resizeMode = RESIZE_MODE_ZOOM
            backgroundPlayer?.playWhenReady = true   // resume a Canvas that was paused on collapse
            true
        } else {
            backgroundPlayer?.release()
            backgroundPlayer = null
            binding?.playerView?.player = null
            false
        }
        applyVideoVisibility(visible)
    }

    @OptIn(UnstableApi::class)
    private fun configureBackgroundPlayerView() {
        binding?.playerView?.subtitleView?.setStyle(
            CaptionStyleCompat(
                Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                EDGE_TYPE_OUTLINE, Color.BLACK, null
            )
        )
        observe(viewModel.serverAndTracks) { applyPlayer() }
    }

    companion object {
        private fun Context.showBackground() = getSettings().showBackground()
        const val DYNAMIC_PLAYER = "dynamic_player"
        const val PLAYER_COLOR = "player_app_color"
        fun Context.isDynamic(): Boolean =
            getSettings().getBoolean(DYNAMIC_PLAYER, true)

        private fun Context.isPlayerColor() =
            getSettings().getBoolean(PLAYER_COLOR, false)

        @OptIn(UnstableApi::class)
        fun getPlayer(
            context: Context, cache: SimpleCache, video: Streamable.Media.Background,
        ): ExoPlayer {
            val cacheFactory = CacheDataSource
                .Factory().setCache(cache)
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(video.request.headers)
                )
            val factory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(cacheFactory)
            val player = ExoPlayer.Builder(context).setMediaSourceFactory(factory).build()
            player.setMediaItem(MediaItem.fromUri(video.request.url.toUri()))
            player.repeatMode = REPEAT_MODE_ONE
            player.volume = 0f
            player.prepare()
            player.play()
            return player
        }
    }
}