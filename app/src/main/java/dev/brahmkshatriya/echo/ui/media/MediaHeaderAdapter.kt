package dev.brahmkshatriya.echo.ui.media

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import androidx.core.text.HtmlCompat
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import android.icu.text.CompactDecimalFormat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Album.Type.Book
import dev.brahmkshatriya.echo.common.models.Album.Type.Compilation
import dev.brahmkshatriya.echo.common.models.Album.Type.EP
import dev.brahmkshatriya.echo.common.models.Album.Type.LP
import dev.brahmkshatriya.echo.common.models.Album.Type.PreRelease
import dev.brahmkshatriya.echo.common.models.Album.Type.Show
import dev.brahmkshatriya.echo.common.models.Album.Type.Single
import androidx.constraintlayout.widget.ConstraintLayout
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemLineBinding
import dev.brahmkshatriya.echo.databinding.ItemMediaHeaderBinding
import dev.brahmkshatriya.echo.databinding.ItemShelfErrorBinding
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.getFinalTitle
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.getMessage
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.media.MediaFragment.Companion.getBundle
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.ui.SimpleItemSpan
import dev.brahmkshatriya.echo.ui.feed.viewholders.MediaViewHolder.Companion.placeHolder
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import dev.brahmkshatriya.echo.utils.ui.UiUtils.toCompactDurationString
import dev.brahmkshatriya.echo.utils.ui.UiUtils.toTimeString
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder

class MediaHeaderAdapter(
    private val listener: Listener,
    private val fromPlayer: Boolean,
) : ScrollAnimRecyclerAdapter<MediaHeaderAdapter.ViewHolder>(), GridAdapter {

    interface Listener {
        fun onRetry(view: View)
        fun onError(view: View, error: Throwable?)
        fun onDescriptionClicked(view: View, extensionId: String?, item: EchoMediaItem?)
        fun openMediaItem(extensionId: String, item: EchoMediaItem)
        fun onFollowClicked(view: View, follow: Boolean)
        fun onSavedClicked(view: View, saved: Boolean)
        fun onLikeClicked(view: View, liked: Boolean)
        fun onPlayClicked(view: View)
        fun onRadioClicked(view: View)
        fun onShareClicked(view: View)
        fun onHideClicked(view: View, hidden: Boolean)
    }

    override val adapter = this

    /**
     * Top inset for the NON-COVER header states, in px. Set from MediaDetailsFragment's insets block.
     *
     * ⚠️ WHY THIS EXISTS AT ALL. Since 2026-09-07 the RecyclerView's own paddingTop is 0 on phones, so
     * item 0 starts at y=0 and the cover sits behind the transparent toolbar — that is the point of the
     * overlap, and it is what lets the fast-scroll rail span the screen. But item 0 is not always the
     * cover: getItemViewType returns 1 (Error) or 2 (Loading) while the item is failing or still loading,
     * and neither of those wants to be under a bar. Nothing else can be at rest up there — the header
     * adapter is first in the Concat and always reports exactly one item — so this covers the whole
     * exposure.
     *
     * Loading is invisible anyway (its holder sets itemView.alpha = 0f) and is padded only for
     * consistency; Error is the state this is actually for.
     */
    // ⚠️ ALWAYS 0 SINCE THE 2026-09-07 OVERLAP REVERT — KEPT, NOT LIVE. Its one writer was
    // MediaDetailsFragment's applyInsets block, which set it to insets.top + ?actionBarSize while the list
    // spanned the full viewport under a transparent toolbar. With @string/appbar_scrolling_view_behavior
    // back on the container the list is positioned below the AppBarLayout, so Error/Loading need no inset
    // of their own and nothing writes this. The field and its use below are therefore inert, and left in
    // place because they are correct for a future overlap attempt — read the five device symptoms in
    // fragment_media.xml first. If you are wondering why a header state is not being inset: it is because
    // it no longer needs to be, not because this broke.
    var topInset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            notifyItemChanged(0)
        }

    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        0 -> Success(parent, listener, fromPlayer)
        1 -> Error(parent, listener)
        2 -> Loading(parent)
        else -> throw IllegalArgumentException("Unknown view type: $viewType")
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        // Success keeps 0 — the cover is MEANT to start at the top of the screen and travel under the
        // toolbar. Error/Loading are inset instead, since the list no longer insets them. See topInset.
        holder.itemView.updatePaddingRelative(top = if (holder is Success) 0 else topInset)
        when (holder) {
            is Success -> {
                val state = result?.getOrNull() ?: return
                holder.bind(state)
            }

            is Error -> {
                val error = result?.exceptionOrNull() ?: return
                holder.bind(error)
            }

            is Loading -> {}
        }
    }

    override fun getItemCount() = 1
    override fun getItemViewType(position: Int) = when (result?.isSuccess) {
        true -> 0
        false -> 1
        null -> 2
    }

    var result: Result<MediaState.Loaded<*>>? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    sealed class ViewHolder(itemView: View) : ScrollAnimViewHolder(itemView)
    class Success(
        parent: ViewGroup,
        private val listener: Listener,
        private val fromPlayer: Boolean,
        private val binding: ItemMediaHeaderBinding = ItemMediaHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ViewHolder(binding.root) {
        val buttons = binding.run {
            listOf(
                followButton, playButton, savedButton, likeButton, hideButton,
                radioButton, shareButton
            )
        }

        init {
            binding.followButton.setOnClickListener {
                listener.onFollowClicked(it, binding.followButton.isChecked)
                it.isEnabled = false
            }
            binding.savedButton.setOnClickListener {
                listener.onSavedClicked(it, binding.savedButton.isChecked)
                it.isEnabled = false
            }
            binding.likeButton.setOnClickListener {
                listener.onLikeClicked(it, binding.likeButton.isChecked)
                it.isEnabled = false
            }
            binding.hideButton.setOnClickListener {
                listener.onHideClicked(it, binding.hideButton.isChecked)
                it.isEnabled = false
            }
            binding.playButton.setOnClickListener {
                listener.onPlayClicked(it)
            }
            binding.radioButton.setOnClickListener {
                listener.onRadioClicked(it)
            }
            binding.shareButton.setOnClickListener {
                listener.onShareClicked(it)
                it.isEnabled = false
            }
        }


        fun configureButtons() {
            val visible = buttons.filter { it.isVisible }
            binding.buttonGroup.isVisible = visible.isNotEmpty()
            val isNotOne = visible.size > 1
            visible.forEachIndexed { index, button ->
                button.isEnabled = true
                if (index == 0 && isNotOne) button.run {
                    updatePaddingRelative(
                        start = if (icon != null) 16.dpToPx(context) else 24.dpToPx(context),
                        end = 24.dpToPx(context)
                    )
                    iconPadding = 8.dpToPx(context)
                    text = contentDescription
                } else button.run {
                    updatePaddingRelative(start = 12.dpToPx(context), end = 12.dpToPx(context))
                    iconPadding = 0
                    text = null
                }
            }
            binding.buttonGroup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = if (isNotOne) ViewGroup.LayoutParams.MATCH_PARENT
                else ViewGroup.LayoutParams.WRAP_CONTENT
                bottomMargin = if (isNotOne) 0 else (-56).dpToPx(binding.root.context)
            }
            binding.description.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                marginEnd = if (isNotOne) 0 else 48.dpToPx(binding.root.context)
            }
        }

        var clickEnabled = true
        var state: MediaState.Loaded<*>? = null

        /**
         * The cover's radius as inflated, before any artist override.
         *
         * ⚠️ THERE IS NO DIMEN FOR THIS. The radius comes from the CardView style's
         * `cardCornerRadius = ?itemCorner` — a THEME ATTRIBUTE (themes.xml maps it to @dimen/item_corner).
         * `R.dimen.item_corner_radius` does not exist and has been reached for twice; read it off the
         * inflated view instead, which also survives a theme change.
         */
        private val defaultCoverRadius = binding.coverContainer.radius

        fun bind(state: MediaState.Loaded<*>) = with(binding) {
            // ── COVER (moved here from MediaFragment on 2026-09-05) ──────────────────────────────────
            // It used to live in fragment_media.xml's CollapsingToolbarLayout, which forced the
            // RecyclerView to start below the header and so made the fast-scroll rail start ~633px down
            // the screen. Loading it here puts it in item 0 and lets the list span the screen.
            //
            // ⚠️ RUNS PER BIND, NOT ONCE — the ViewHolder is reused. In MediaFragment this sat in an
            // observe() that fired once per result, so a one-shot was correct there and is NOT correct
            // here: a recycled holder that skipped the artist branch would keep the previous item's
            // radius and matchConstraintMaxWidth. Hence the explicit else.
            coverContainer.run {
                if (state.item is Artist) {
                    // Artist covers are hard-capped at 240dp and circular — INDEPENDENT of
                    // @dimen/media_header_cover_size (250dp), which @style/ItemCover applies as
                    // constraintWidth_max/constraintHeight_max and which 2026-06-15 proved is live.
                    // Both caps must survive; this is the tighter one and applies to artists only.
                    val maxWidth = 240.dpToPx(context)
                    radius = maxWidth.toFloat()
                    updateLayoutParams<ConstraintLayout.LayoutParams> {
                        matchConstraintMaxWidth = maxWidth
                    }
                } else {
                    // Restore the geometry a fresh holder has. `defaultCoverRadius` is captured at
                    // construction from the inflated view rather than read from a dimen: the radius comes
                    // from the CardView style's `cardCornerRadius = ?itemCorner`, a THEME ATTRIBUTE, so
                    // hardcoding or re-resolving a dimen here would silently diverge if the theme changes.
                    // matchConstraintMaxWidth = 0 means "no explicit max", handing the cap back to
                    // @style/ItemCover's constraintWidth_max (media_header_cover_size).
                    radius = defaultCoverRadius
                    updateLayoutParams<ConstraintLayout.LayoutParams> {
                        matchConstraintMaxWidth = 0
                    }
                }
            }
            // No size argument: loadInto's params are (placeholder, errorDrawable) and Coil sizes from
            // the view, which is still capped by @style/ItemCover -> media_header_cover_size. So the
            // request is identical to the one MediaFragment made before the move.
            //
            // ⚠️ NO STALE-DELIVERY GUARD IS NEEDED HERE, and don't add one. loadInto uses
            // request.target(imageView) — a Coil ViewTarget — so enqueuing on the same ImageView disposes
            // the in-flight request and a recycled holder cannot be painted by the previous item's
            // delivery. This is the OPPOSITE of PlayerTrackAdapter's cover, which uses loadWithThumb's
            // lambda target, gets no ViewTarget and therefore no automatic cancellation — which is why
            // that one needs pendingMediaId/lastBoundMediaId and this one does not.
            //
            // The expanded title, restored 2026-09-07 — see the note in item_media_header.xml.
            // state.item is the same source MediaFragment uses for the toolbar title, so the two
            // cannot drift apart.
            title.text = state.item.title.trim()
            state.item.cover.loadInto(cover, null, state.item.placeHolder)

            this@Success.state = state
            followButton.isVisible = state.isFollowed != null
            followButton.isChecked = state.isFollowed ?: false
            followButton.contentDescription = root.context.getString(
                if (state.isFollowed == true) R.string.unfollow else R.string.follow
            )

            savedButton.isVisible = state.isSaved != null && !(state.item is Playlist && state.item.isEditable) && !fromPlayer
            savedButton.isChecked = state.isSaved ?: false
            savedButton.contentDescription = root.context.getString(
                if (state.isSaved == true) R.string.unsave else R.string.save
            )

            likeButton.isVisible = state.isLiked != null && !fromPlayer
            likeButton.isChecked = state.isLiked ?: false
            likeButton.contentDescription = root.context.getString(
                if (state.isLiked == true) R.string.unlike else R.string.like
            )

            hideButton.isVisible = state.isHidden != null
            hideButton.isChecked = state.isHidden ?: false
            hideButton.contentDescription = root.context.getString(
                if (state.isHidden == true) R.string.unhide else R.string.hide
            )

            playButton.isVisible = state.item is Track && !fromPlayer && state.item.isPlayable == Track.Playable.Yes
            radioButton.isVisible = state.showRadio && !fromPlayer
            shareButton.isVisible = state.showShare && !fromPlayer
            configureButtons()

            explicit.isVisible = state.item.isExplicit
            followers.isVisible = state.followers != null
            followers.text = state.followers?.let {
                val formatter = CompactDecimalFormat.getInstance()
                root.context.getString(R.string.x_followers, formatter.format(it))
            }
            val span =
                root.context.getSpan(true, state.extensionId, state.item) { id, item ->
                    clickEnabled = false
                    listener.openMediaItem(id, item)
                    description.post { clickEnabled = true }
                }
            description.text = span
            description.isVisible = span.isNotEmpty()
        }

        init {
            binding.run {
                description.movementMethod = LinkMovementMethod.getInstance()
                description.setOnClickListener {
                    if (clickEnabled) listener.onDescriptionClicked(
                        it,
                        state?.extensionId,
                        state?.item
                    )
                }
            }
        }
    }

    class Error(
        parent: ViewGroup,
        listener: Listener,
        private val binding: ItemShelfErrorBinding = ItemShelfErrorBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ViewHolder(binding.root) {
        var throwable: Throwable? = null

        init {
            binding.errorView.setOnClickListener {
                listener.onError(binding.error, throwable)
            }
            binding.retry.setOnClickListener {
                listener.onRetry(it)
            }
        }

        fun bind(throwable: Throwable) {
            this.throwable = throwable
            binding.error.run {
                transitionName = throwable.hashCode().toString()
                text = context.getFinalTitle(throwable)
            }
        }
    }

    class Loading(
        parent: ViewGroup,
        binding: ItemLineBinding = ItemLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ViewHolder(binding.root) {
        init {
            itemView.alpha = 0f
        }
    }

    companion object {
        private const val MAX_DESC_TEXT = 144
        private fun String.ellipsize() = if (length > MAX_DESC_TEXT) {
            substring(0, MAX_DESC_TEXT) + "..."
        } else this

        private const val DIVIDER = " • "
        private fun String.parseHtml(): String {
            return HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_COMPACT)
                .toString().trim()
        }

        fun Context.getSpan(
            compact: Boolean,
            extensionId: String,
            item: EchoMediaItem,
            openMediaItem: (String, EchoMediaItem) -> Unit = { a, b -> },
        ): SpannableString = when (item) {
            is EchoMediaItem.Lists -> {
                val madeBy = item.artists.joinToString(", ") { it.name }
                val span = SpannableString(buildString {
                    val firstRow = when (item) {
                        is Playlist -> listOfNotNull(
                            getString(item.typeInt),
                            item.date?.toString(),
                            item.duration?.toCompactDurationString()
                        ).joinToString(DIVIDER)
                        else -> listOfNotNull(
                            getString(item.typeInt),
                            item.date?.toString(),
                        ).joinToString(DIVIDER)
                    }
                    val secondRow = when (item) {
                        is Album -> ""
                        is Playlist -> ""
                        else -> listOfNotNull(
                            item.toTrackString(this@getSpan),
                            item.duration?.toTimeString()
                        ).joinToString(DIVIDER)
                    }
                    if (firstRow.isNotEmpty()) appendLine(firstRow)
                    if (secondRow.isNotEmpty()) appendLine(secondRow)
                    val desc = item.description?.parseHtml()?.takeIf { it.isNotEmpty() }
                    if (desc != null) {
                        appendLine()
                        appendLine(if (compact) desc.ellipsize() else desc)
                    }
                    if (madeBy.isNotEmpty()) {
                        if (item is Album) appendLine()
                        appendLine(madeBy)
                    }
                    if (item.label != null) {
                        appendLine()
                        appendLine(item.label)
                    }
                }.trimEnd('\n').trimStart('\n'))
                val madeByIndex = span.indexOf(madeBy)
                item.artists.forEach {
                    val start = span.indexOf(it.name, madeByIndex)
                    if (start != -1) {
                        val end = start + it.name.length
                        val clickableSpan = SimpleItemSpan(this) {
                            openMediaItem(extensionId, it)
                        }
                        span.setSpan(
                            clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                span
            }

            is Artist -> {
                val desc = if (compact) item.bio?.parseHtml()?.ellipsize() 
                    else item.bio?.parseHtml()
                SpannableString(desc ?: "")
            }

            is Track -> {
                SpannableString(buildString {
                    val casualRow = listOfNotNull(
                        item.duration?.toTimeString(),
                        item.releaseDate?.toString()
                    ).joinToString(DIVIDER)
                    if (casualRow.isNotEmpty()) appendLine(casualRow)

                    val notPlayable = item.playableString(this@getSpan)
                    if (!notPlayable.isNullOrEmpty()) {
                        appendLine()
                        appendLine(notPlayable)
                    }

                    val desc = item.description?.parseHtml()
                    if (desc != null) {
                        appendLine()
                        appendLine(if (compact) desc.ellipsize() else desc)
                    }

                    val writtenBy = item.extras["CONTRIB_AUTHOR"]
                    val contribs = listOf(
                        "CONTRIB_AUTHOR" to "WRITTEN BY",
                        "CONTRIB_COMPOSER" to "COMPOSED BY",
                        "CONTRIB_PRODUCER" to "PRODUCED BY",
                        "CONTRIB_ENGINEER" to "ENGINEER"
                    ).mapNotNull { (key, label) ->
                        val names = item.extras[key]
                        if (names.isNullOrEmpty()) return@mapNotNull null
                        if (key == "CONTRIB_COMPOSER" && names == writtenBy) return@mapNotNull null
                        label to names
                    }
                    if (contribs.isNotEmpty()) {
                        appendLine()
                        contribs.forEachIndexed { idx, (label, names) ->
                            if (idx > 0) appendLine()
                            appendLine(label)
                            appendLine(names)
                        }
                    }

                    val genres = item.genres.joinToString(", ")
                    val isrc = item.isrc
                    val albumLabel = item.album?.label
                    val discTrack = listOfNotNull(
                        item.albumDiscNumber?.let { getString(R.string.disc_number_n, it) },
                        item.albumOrderNumber?.let { getString(R.string.album_order_n, it) }
                    ).joinToString(DIVIDER)
                    val specs = listOfNotNull(
                        if (genres.isNotEmpty()) "GENRE" to genres else null,
                        if (isrc != null) "ISRC" to isrc else null,
                        if (albumLabel != null) "LABEL" to albumLabel else null,
                        if (discTrack.isNotEmpty()) "DISC / TRACK" to discTrack else null
                    )
                    if (specs.isNotEmpty()) {
                        appendLine()
                        specs.forEachIndexed { idx, (specLabel, specValue) ->
                            if (idx > 0) appendLine()
                            appendLine(specLabel)
                            appendLine(specValue)
                        }
                    }
                }.trimStart('\n').trimEnd('\n'))
            }
        }

        fun Context.unfuckedString(
            numberStringId: Int, nStringId: Int, count: Int,
        ) = runCatching {
            resources.getQuantityString(numberStringId, count, count)
        }.getOrNull() ?: getString(nStringId, count)

        fun Fragment.getMediaHeaderListener(viewModel: MediaDetailsViewModel) = object : Listener {
            override fun onRetry(view: View) {
                viewModel.refresh()
            }

            override fun onError(view: View, error: Throwable?) {
                error ?: return
                requireActivity().getMessage(error, view).action?.handler?.invoke()
            }

            override fun openMediaItem(extensionId: String, item: EchoMediaItem) {
                openFragment<MediaFragment>(null, getBundle(extensionId, item, false))
            }

            override fun onFollowClicked(view: View, follow: Boolean) {
                viewModel.followItem(follow)
            }

            override fun onSavedClicked(view: View, saved: Boolean) {
                if (!saved) {
                    val item = viewModel.itemResultFlow.value?.getOrNull()?.item
                    if (item is Playlist && !item.isEditable) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(getString(R.string.remove_from_library_confirm, item.title))
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.remove) { _, _ -> viewModel.saveToLibrary(false) }
                            .show()
                        return
                    }
                }
                viewModel.saveToLibrary(saved)
            }

            override fun onLikeClicked(view: View, liked: Boolean) {
                viewModel.likeItem(liked)
            }

            override fun onHideClicked(view: View, hidden: Boolean) {
                viewModel.hideItem(hidden)
            }

            override fun onPlayClicked(view: View) {
                val (extensionId, item, loaded) = viewModel.getItem() ?: return
                val vm by activityViewModels<PlayerViewModel>()
                vm.play(extensionId, item, loaded)
            }

            override fun onRadioClicked(view: View) {
                val (extensionId, item, loaded) = viewModel.getItem() ?: return
                val vm by activityViewModels<PlayerViewModel>()
                vm.radio(extensionId, item, loaded)
            }

            override fun onShareClicked(view: View) {
                viewModel.onShare()
            }

            override fun onDescriptionClicked(
                view: View, extensionId: String?, item: EchoMediaItem?,
            ) {
                item ?: return
                extensionId ?: return
                val context = requireContext()
                var dialog: AlertDialog? = null
                val builder = MaterialAlertDialogBuilder(context)
                builder.setTitle(item.title)
                builder.setMessage(context.getSpan(false, extensionId, item) { m, n ->
                    openMediaItem(m, n)
                    dialog?.dismiss()
                })
                builder.setPositiveButton(getString(R.string.okay)) { d, _ ->
                    d.dismiss()
                }
                dialog = builder.create()
                dialog.show()
                val text = dialog.findViewById<TextView>(android.R.id.message)!!
                text.movementMethod = LinkMovementMethod.getInstance()
            }
        }

        val EchoMediaItem.Lists.typeInt
            get() = when (this) {
                is Album -> when (type) {
                    PreRelease -> R.string.pre_release
                    Single -> R.string.single
                    EP -> R.string.ep
                    LP -> R.string.lp
                    Compilation -> R.string.compilation
                    Show -> R.string.show
                    Book -> R.string.book
                    null -> R.string.album
                }

                is Playlist -> R.string.playlist
                is Radio -> R.string.radio
            }

        fun EchoMediaItem.Lists.toTrackString(context: Context) = context.run {
            val tracks = trackCount?.toInt()
            if (tracks != null) {
                when (type) {
                    PreRelease, Single, EP, LP, Compilation -> unfuckedString(
                        R.plurals.number_songs, R.string.n_songs, tracks
                    )

                    Show -> unfuckedString(
                        R.plurals.number_episodes, R.string.n_episodes, tracks
                    )

                    Book -> unfuckedString(
                        R.plurals.number_chapters, R.string.n_chapters, tracks
                    )

                    null -> unfuckedString(
                        R.plurals.number_tracks, R.string.n_tracks, tracks
                    )
                }
            } else null
        }

        fun Track.playableString(context: Context) = when (val play = isPlayable) {
            is Track.Playable.No -> context.getString(R.string.not_playable_x, play.reason)
            Track.Playable.Yes -> null
            Track.Playable.RegionLocked -> context.getString(R.string.unavailable_in_your_region)
            Track.Playable.Unreleased -> if (releaseDate != null) context.getString(
                R.string.releases_on_x, releaseDate.toString()
            ) else context.getString(R.string.not_yet_released)
        }
    }
}