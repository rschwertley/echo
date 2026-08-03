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
        // TEMPORARY (PIPER-DIAG) — one line per RAW playlist.getSongs entry, dumping top-level + FALLBACK
        // id/name/art fields + the FALLBACK key list, so a SINGLE capture answers every open question:
        //  • discriminator safety: do correctly-arted tracks (topAlbPic=present) also carry a FALLBACK, or
        //    only broken ones (topAlbPic=BLANK)?  (compare fb=yes/no against topAlbPic across tracks)
        //  • is "different id" (same=false) a reliable "broken" signal, or can a same=false track still
        //    have topAlbPic=present (good art)?
        //  • re-resolve necessity: is FALLBACK full (fbKeys shows ART_ID/ALB_ID/ALB_PICTURE) or thin
        //    (fbKeys=[SNG_ID])?
        // Remove after capture.
        parser.run {
            dataArray.mapNotNull { it as? JsonObject }.forEachIndexed { i, entry ->
                val d = entry.unwrap()
                val topPic = if (!d.str("ALB_PICTURE").isNullOrBlank()) "present" else "BLANK"
                val fb = d["FALLBACK"] as? JsonObject
                val fbPart = if (fb == null) "fb=no" else {
                    val fbPic = if (!fb.str("ALB_PICTURE").isNullOrBlank()) "present" else "BLANK"
                    "fb=yes fbSng=${fb.str("SNG_ID")} fbArt=${fb.str("ART_ID")}/'${fb.str("ART_NAME")}' " +
                        "fbAlb=${fb.str("ALB_ID")}/'${fb.str("ALB_TITLE")}' fbAlbPic=$fbPic " +
                        "same=${d.str("SNG_ID") == fb.str("SNG_ID")} fbKeys=[${fb.keys.joinToString(",")}]"
                }
                android.util.Log.d(
                    "PIPER-DIAG",
                    "#$i sng=${d.str("SNG_ID")} art=${d.str("ART_ID")}/'${d.str("ART_NAME")}' " +
                        "alb=${d.str("ALB_ID")}/'${d.str("ALB_TITLE")}' topAlbPic=$topPic $fbPart"
                )
            }
        }
        // Lean entries from playlist.getSongs (STORED records — may carry the wrong same-named-artist twin).
        val leanTracks = dataArray.mapNotNull { it as? JsonObject }
            .map { parser.run { it.toTrack() } }

        // Canonical re-resolve by SNG_ID: song.getListData returns each track's canonical record (correct
        // ART_ID/ALB_ID/ALB_PICTURE/ARTISTS). Per-track graceful fallback — a track absent from (or
        // un-parseable in) the canonical response keeps its lean entry. Track.id == SNG_ID, so keys line up.
        val sngIds = leanTracks.map { it.id }.filter { it.isNotEmpty() }
        val canonicalMap = api.getListData(sngIds).mapNotNull { obj ->
            val id = parser.run { obj.unwrap().str("SNG_ID") }
            if (id.isNullOrEmpty()) null else id to obj
        }.toMap()

        val resolved = leanTracks.map { lean ->
            val canonical = canonicalMap[lean.id] ?: return@map lean
            runCatching { parser.run { canonical.toTrack() } }.getOrElse { lean }
        }

        resolved.mapIndexed { index, track ->
            track.copy(
                extras = track.extras + mapOf(
                    "NEXT" to resolved.getOrNull(index + 1)?.id.orEmpty(),
                    "playlist_id" to playlist.id
                )
            )
        }
    }.toFeed()
}