package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeezerPlaylistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    // Deezer has no related-content shelves for playlists. Return null (not an empty Feed) so
    // the feed pipeline treats it as "no section" and renders nothing. An empty but non-null
    // Feed is interpreted as "a section that loaded empty" and engages the empty-state
    // placeholder (EmptyAdapter), which shows a spinner/illustration we don't want here.
    // `playlist` is unused BY DESIGN — the answer is null regardless of which playlist is asked about.
    // Kept rather than removed: this is the sole delegate for DeezerExtension.loadFeed(playlist), which
    // overrides PlaylistClient.loadFeed(playlist) in :common, and it mirrors the sibling
    // DeezerArtistClient.getShelves(artist) which DOES use its argument. Dropping it would desync both.
    @Suppress("UNUSED_PARAMETER")
    fun getShelves(playlist: Playlist): Feed<Shelf>? = null

    suspend fun loadPlaylist(playlist: Playlist): Playlist {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        return parser.run { resultsObject.toPlaylist() }
    }

    fun loadTracks(playlist: Playlist): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        // Tracks come from the dedicated playlist.getSongs (the app/deezer-py authoritative path), NOT from
        // deezer.pagePlaylist's inline SONGS (a summary that can carry a wrong same-named-artist twin).
        // pagePlaylist is still used for playlist METADATA in loadPlaylist above.
        val jsonObject = api.playlistSongs(playlist)
        // `results` is present for ANY valid response (empty playlists included), so a missing `results`
        // is a genuine Deezer error response (callApi returns non-CSRF errors un-thrown). Surface it with
        // Deezer's error message if available — do NOT degrade to empty, which would mask the failure as an
        // empty playlist. (message extraction is runCatching-guarded so it can never throw over the real error.)
        val results = jsonObject["results"]?.jsonObject ?: run {
            val message = runCatching {
                jsonObject["error"]?.jsonObject?.entries
                    ?.joinToString { (key, value) -> value.jsonPrimitive.contentOrNull ?: key }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            throw Exception(message ?: "Failed to load playlist tracks")
        }
        // playlist.getSongs returns tracks at results.data[] (flat — NOT results.SONGS.data). Absent/empty
        // (empty or edge playlist) degrades to an empty list rather than NPE. PagedData.Single (non-paginated),
        // so an empty result can never mask a mid-pagination gap.
        val dataArray = results["data"]?.jsonArray ?: JsonArray(emptyList())
        // playlist.getSongs pre-substitutes an unavailable original with a playable-but-dead/mis-attributed
        // track at TOP-LEVEL and moves the CORRECT catalog data into a full FALLBACK object. So when a
        // FALLBACK exists, take the DISPLAY fields (artists/album/cover) from it while keeping the top-level
        // SNG_ID as the track id for STREAMING (top-level is the confirmed-playable one). No FALLBACK →
        // top-level is already correct, use as-is. (Verified on Piper/Baby Beluga/Dora; fb=no unchanged.)
        val baseTracks = dataArray.filterIsInstance<JsonObject>().map { entry ->
            parser.run {
                val d = entry.unwrap()
                val top = d.toTrack()
                val fb = d["FALLBACK"] as? JsonObject
                if (fb == null) top
                else {
                    val fbTrack = fb.toTrack()
                    top.copy(
                        artists = fbTrack.artists,
                        album = fbTrack.album,
                        cover = fbTrack.cover,
                        background = fbTrack.background
                    )
                }
            }
        }
        baseTracks.mapIndexed { index, track ->
            track.copy(
                extras = track.extras + mapOf(
                    "NEXT" to baseTracks.getOrNull(index + 1)?.id.orEmpty(),
                    "playlist_id" to playlist.id
                )
            )
        }
    }.toFeed()
}