package dev.brahmkshatriya.echo.ui.player

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.databinding.ItemClickPanelsBinding
import dev.brahmkshatriya.echo.databinding.ItemPlayerTrackBinding
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.unloadedCover
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyHorizontalInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.brahmkshatriya.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.brahmkshatriya.echo.utils.image.ImageUtils.getCachedDrawable
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadWithThumb
import dev.brahmkshatriya.echo.utils.ui.GestureListener
import dev.brahmkshatriya.echo.utils.ui.GestureListener.Companion.handleGestures
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isLandscape
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isRTL
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max

class PlayerTrackAdapter(
    private val uiViewModel: UiViewModel,
    private val current: MutableStateFlow<PlayerState.Current?>,
    private val listener: Listener
) : ListAdapter<MediaItem, PlayerTrackAdapter.ViewHolder>(DiffCallback) {

    interface Listener {
        fun onClick()
        fun onLongClick() {}
        fun onStartDoubleClick() {}
        fun onEndDoubleClick() {}
    }

    object DiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem) =
            oldItem.mediaId == newItem.mediaId

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem == newItem
        }
    }

    inner class ViewHolder(
        private val binding: ItemPlayerTrackBinding
    ) : ScrollAnimViewHolder(binding.root) {

        private val context = binding.root.context

        private val collapsedPadding = 8.dpToPx(context)
        private val targetZ = collapsedPadding.toFloat()
        private val size = binding.root.resources.getDimension(R.dimen.collapsed_cover_size).toInt()
        private var targetScale = 0f
        private var targetX = 0
        private var targetY = 0

        private val cover = binding.playerTrackCoverContainer
        private var currentCoverHeight = size
        private var currCoverRound = 0f
        private val isLandscape = context.isLandscape()
        fun updateCollapsed() = uiViewModel.run {
            val insets = if (!isLandscape) systemInsets.value else getCombined()
            // Full-width mini-bar: land the morphed cover flush at insets.start (matching the overlay
            // collapsedTrackCover), NOT collapsedPadding+insets.start — otherwise the two covers sit
            // 8dp apart and the duplicate shows. Both now stack at the same flush position.
            val targetPosX = if (context.isRTL()) insets.end else insets.start
            val targetPosY = if (playerSheetState.value != STATE_EXPANDED) 0
            else collapsedPadding + systemInsets.value.top
            targetX = targetPosX - cover.left
            targetY = targetPosY - cover.top
            currentCoverHeight = cover.height.takeIf { it > 0 } ?: currentCoverHeight
            targetScale = size.toFloat() / currentCoverHeight

            // The `playerSheetOffset >= 1f` conjunct is LOAD-BEARING, not defensive. playerSheetState is a
            // SETTLED-STATES-ONLY signal: UiViewModel gates its write with `if (!isFinalState(newState))
            // return`, so DRAGGING/SETTLING never reach it and the value still reads EXPANDED for the whole
            // of a collapse drag. Keying the branch on state alone therefore sent every frame of that drag
            // into the EXPANDED arm, where `offset` comes from moreSheetOffset (0, Up Next closed) and
            // playerSheetOffset is not read at all — a constant. updateCollapsed() still ran per frame
            // (observe(playerSheetOffset) fires) but recomputed the SAME transform, so the cover did not
            // morph while BottomSheetBehavior kept translating the sheet: the whole page slid instead.
            // Expanding was unaffected because that drag starts from COLLAPSED and was already on the else
            // arm. That directional break arrived with the state-gate in 1bff9b02 (2026-07-16); the geometry
            // below is unchanged since 4be12e0d (2026-07-01) and was never at fault.
            //
            // Reading playerSheetOffset makes the test "is the player sheet ACTUALLY fully expanded" rather
            // than "did it last settle there". Exactness is guaranteed, not hoped for: onStateChanged forces
            // onSlide(view, if (EXPANDED) 1f else 0f) on every settle, so the resting value is exactly 1f.
            // `>=` covers fling overshoot; max(0f, …) below still covers the negative HIDDEN direction.
            //
            // Up Next is NOT affected. It is a separate BottomSheetBehavior (setupPlayerMoreBehavior) whose
            // callback writes moreSheetOffset alone. With it open the player rests expanded (offset exactly
            // 1f, state EXPANDED), so this arm stays selected; dragging its top bar down to dismiss moves
            // moreSheetOffset only, and that gesture tracks frame by frame exactly as before. There is no
            // reachable case of the player sheet being dragged below expanded while Up Next is open, which
            // is why this needs no max(moreOffset, 1 - slide) blend.
            //
            // Deliberately NOT fixed by un-gating playerSheetState: republishing DRAGGING/SETTLING would
            // reach every other observer of that flow — PlayerFragment's bgImage.pause(), the
            // emit(playerBgVisible, false) on COLLAPSED and the applyPlayer() re-run are all unguarded —
            // and would undo 1bff9b02's setState crash fix. One consumer was wrong; one consumer is fixed.
            val playerFullyExpanded = playerSheetOffset.value >= 1f
            val (collapsedY, offset) =
                if (playerSheetState.value == STATE_EXPANDED && playerFullyExpanded)
                    systemInsets.value.top to if (isLandscape) 0f else moreSheetOffset.value
                else -collapsedPadding to 1 - max(0f, playerSheetOffset.value)

            val inv = 1 - offset
            binding.playerCollapsed.root.run {
                translationY = collapsedY - size * inv * 2
                alpha = offset
            }
            if (isLandscape) binding.clickPanel.root.scaleX = 0.5f + 0.5f * inv
            val extraY = if (!isPlayerVisible) 0f else {
                val toMoveY = binding.playerControlsPlaceholder.top - cover.top
                toMoveY * inv
            }
            val extraX = if (!isPlayerVisible) 0f else {
                val toMoveX = binding.playerControlsPlaceholder.left - cover.left
                toMoveX * inv
            }
            cover.run {
                scaleX = if (!isPlayerVisible) 1 + (targetScale - 1) * offset else targetScale
                scaleY = scaleX
                translationX = targetX * offset + extraX
                translationY = targetY * offset + extraY
                translationZ = targetZ * (1 - offset)
                currCoverRound = collapsedPadding / scaleX
                invalidateOutline()
            }
        }

        fun updateInsets() = uiViewModel.run {
            val (v, h) = if (!isLandscape) 64 to 0 else 0 to 24
            binding.constraintLayout.applyInsets(systemInsets.value, v, h)
            val insets = if (isLandscape) getCombined() else systemInsets.value
            binding.playerCollapsed.root.applyHorizontalInsets(insets)
            binding.playerControlsPlaceholder.run {
                updateLayoutParams {
                    height = playerControlsHeight.value
                }
                doOnLayout {
                    updateCollapsed()
                    cover.doOnLayout { updateCollapsed() }
                }
            }

            updateCollapsed()
        }

        fun updateColors() {
            binding.playerCollapsed.run {
                val colors = uiViewModel.playerColors.value ?: context.defaultPlayerColors()
                collapsedTrackTitle.setTextColor(colors.onBackground)
                collapsedTrackArtist.setTextColor(colors.onBackground)
            }
        }

        // Set ONLY by a real Coil delivery (loadWithThumb's onDelivered). This is the retry guard, so it
        // must never be satisfied by a cached thumbnail or a placeholder: it previously took its value
        // from the callback that also fires for the synchronous pre-set, so a disk-cached thumb marked
        // the cover "loaded" and disarmed retryLoad for the life of the ViewHolder — defeating the one
        // path written to recover a load that never arrived.
        private var coverDrawable: Drawable? = null

        // What is currently PAINTED on the ImageView, thumbnail included. Split out from coverDrawable
        // so the dynamic-colour listener keeps seeing every paint (its previous behaviour) while the
        // retry guard above sees only real deliveries. Feeding it coverDrawable instead would blank the
        // player colours on every thumb-only paint.
        private var paintedDrawable: Drawable? = null

        // Set ONLY on a terminal outcome, for the same reason. Assigning it before the async load meant a
        // load that never delivered still marked the track bound, so no later rebind of the same track
        // could re-enter the cover block and re-run the synchronous pre-set.
        private var lastBoundMediaId: String? = null

        // ═══ WHAT THE 2026-08-24 FIX DID AND DID NOT DO - read before trusting the guards ═══
        // It fixed the SEALING, not the failure. Two bookkeeping defects made a failed cover load
        // permanent: coverDrawable was set from the SYNCHRONOUS thumbnail (disarming retryLoad's guard
        // with a placeholder) and lastBoundMediaId was recorded BEFORE completion (so no rebind could
        // re-issue). Fixing both made the recovery paths reachable again.
        // It never established WHY the load fails, and it never guarded the PAINT. onDelivered below is
        // guarded; the paint callbacks were not, until 2026-08-29. Do not read the guarded onDelivered and
        // assume the pixels are covered - they are separate callbacks with separate guards.
        // The failure mode that survived: a load that DELIVERS CORRECTLY, then a stale delivery for a
        // previous track repaints the same recycled ImageView afterwards. The bookkeeping is then
        // CORRECT - coverDrawable and lastBoundMediaId both refer to the right track - so every recovery
        // path is correctly disarmed while the pixels are wrong, permanently, until something rebinds.
        // That is why guarding the paint matters and why the sealing fix alone could not have caught it.

        // The mediaId this holder is currently trying to show. A late delivery from a superseded load
        // must not write bookkeeping for a track the holder has already moved off, or the next bind for
        // the real track would see a mismatch and re-issue. Compared by value inside onDelivered.
        private var pendingMediaId: String? = null

        fun applyDrawable() {
            val index = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
            val item = getItem(index) ?: return
            val curr = current.value?.mediaItem
            if (curr != item) return
            val drawable = paintedDrawable
            currentDrawableListener?.invoke(drawable)
        }

        fun retryLoad(item: MediaItem?) {
            if (coverDrawable != null) return
            val old = item?.unloadedCover?.getCachedDrawable(binding.root.context)
            val boundId = item?.mediaId
            pendingMediaId = boundId
            item?.track?.cover.loadWithThumb(
                binding.playerTrackCover, old,
                onDelivered = { drawable ->
                    if (pendingMediaId == boundId) {
                        coverDrawable = drawable
                        lastBoundMediaId = boundId
                    }
                }
            ) {
                if (pendingMediaId != boundId) return@loadWithThumb
                val image = it
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.art_music, context.theme)
                setImageDrawable(image)
                paintedDrawable = it
                applyDrawable()
            }
        }

        // Belt for the screen-off wake case, driven from PlayerFragment.onResume. Re-issues the cover load
        // UNCONDITIONALLY, ignoring the coverDrawable / lastBoundMediaId guards, because in the overwrite
        // scenario those are correctly SATISFIED: the right cover did deliver and set them, and a stale
        // delivery then repainted over it. Nothing else can notice that, so nothing else re-issues.
        // This is a belt, not the fix. Its ordering is not guaranteed - it can still lose to a slower
        // in-flight stale request - which is exactly why the paint guard above is the real remedy. It is
        // here because the two candidate mechanisms have different fixes and this covers the other one:
        // if the failure is a load that never delivers, a fresh load in a live window is what recovers it.
        fun forceReloadCover() {
            val index = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return
            val item = getItem(index) ?: return
            val boundId = item.mediaId
            pendingMediaId = boundId
            val old = item.unloadedCover?.getCachedDrawable(binding.root.context)
            item.track.cover.loadWithThumb(
                binding.playerTrackCover, old,
                onDelivered = { drawable ->
                    if (pendingMediaId == boundId) {
                        coverDrawable = drawable
                        lastBoundMediaId = boundId
                    }
                }
            ) {
                if (pendingMediaId != boundId) return@loadWithThumb
                val image = it
                    ?: ResourcesCompat.getDrawable(resources, R.drawable.art_music, context.theme)
                setImageDrawable(image)
                paintedDrawable = it
                applyDrawable()
            }
        }

        fun bind(item: MediaItem?) {
            binding.playerCollapsed.run {
                collapsedTrackTitle.text = item?.track?.title
                collapsedTrackArtist.text = item?.track?.artists?.joinToString(", ") { it.name }
            }
            if (item?.mediaId != lastBoundMediaId) {
                // lastBoundMediaId is deliberately NOT assigned here — see its declaration. Until a
                // terminal outcome arrives this holder stays rebindable, so a rebind of the same track
                // re-enters and re-runs the synchronous pre-set below, which repaints the ImageView from
                // the disk cache with no delivery required.
                val boundId = item?.mediaId
                pendingMediaId = boundId
                coverDrawable = null
                val old = item?.unloadedCover?.getCachedDrawable(binding.root.context)
                item?.track?.cover.loadWithThumb(
                    binding.playerTrackCover, old,
                    onDelivered = { drawable ->
                        if (pendingMediaId == boundId) {
                            coverDrawable = drawable
                            lastBoundMediaId = boundId
                        }
                    }
                ) {
                    if (pendingMediaId != boundId) return@loadWithThumb
                    val image = it
                        ?: ResourcesCompat.getDrawable(resources, R.drawable.art_music, context.theme)
                    setImageDrawable(image)
                    paintedDrawable = it
                    applyDrawable()
                }
            }
            updateInsets()
            updateColors()
        }

        init {
            cover.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        0, 0, currentCoverHeight, currentCoverHeight, currCoverRound
                    )
                }
            }
            cover.clipToOutline = true
            cover.doOnLayout { updateInsets() }
            binding.clickPanel.configureClicking(listener, uiViewModel)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPlayerTrackBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    var recyclerView: RecyclerView? = null
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        holder.updateInsets()
        holder.updateColors()
        holder.applyDrawable()
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) holder.retryLoad(getItem(pos))
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        holder.updateInsets()
        holder.updateColors()
        holder.applyDrawable()
    }

    private fun onEachViewHolder(block: ViewHolder.() -> Unit) {
        val recyclerView = recyclerView ?: return
        recyclerView.run {
            for (it in 0 until childCount) {
                val viewHolder = getChildViewHolder(getChildAt(it)) as? ViewHolder
                viewHolder?.block()
            }
        }
    }

    fun moreOffsetUpdated() = onEachViewHolder { updateCollapsed() }
    fun playerOffsetUpdated() = onEachViewHolder { updateCollapsed() }
    fun playerSheetStateUpdated() = onEachViewHolder { updateInsets() }
    fun insetsUpdated() = onEachViewHolder { updateInsets() }
    fun playerControlsHeightUpdated() = onEachViewHolder { updateInsets() }
    fun onColorsUpdated() = onEachViewHolder { updateColors() }
    // Called from PlayerFragment.onResume - see forceReloadCover for why this exists and why it is a belt
    // rather than the fix. Hits every ATTACHED holder, not just the visible one: neighbouring pages are
    // equally exposed to a stale delivery and equally invisible to the guards.
    fun refreshCovers() = onEachViewHolder { forceReloadCover() }

    fun onCurrentUpdated() {
        onEachViewHolder { applyDrawable() }
        if (current.value == null) currentDrawableListener?.invoke(null)
    }

    private var isPlayerVisible = false
    fun updatePlayerVisibility(visible: Boolean) {
        isPlayerVisible = visible
        onEachViewHolder { updateInsets() }
    }

    var currentDrawableListener: ((Drawable?) -> Unit)? = null

    companion object {
        fun ItemClickPanelsBinding.configureClicking(listener: Listener, uiViewModel: UiViewModel) {
            start.handleGestures(object : GestureListener {
                override val onClick = listener::onClick
                override val onLongClick = listener::onLongClick
                override val onDoubleClick: (() -> Unit)?
                    get() = if (uiViewModel.playerSheetState.value != STATE_EXPANDED) null
                    else listener::onStartDoubleClick
            })
            end.handleGestures(object : GestureListener {
                override val onClick = listener::onClick
                override val onLongClick = listener::onLongClick
                override val onDoubleClick: (() -> Unit)?
                    get() = if (uiViewModel.playerSheetState.value != STATE_EXPANDED) null
                    else listener::onEndDoubleClick
            })
        }
    }
}