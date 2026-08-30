package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

class DeezerAlbumClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    companion object {
        private const val HANDOFF_TTL_MS = 30_000L
    }

    private suspend fun results(album: Album) =
        (if (album.type == Album.Type.Show) api.show(album) else api.album(album))["results"]!!.jsonObject

    // ══ SINGLE-SLOT HANDOFF: loadAlbum -> loadTracks ══
    // A cold album open issued THE SAME request twice. loadAlbum and loadTracks both call results(album),
    // which is one api.album() (or api.show()) call, and the response already contains both halves -
    // loadAlbum throws away SONGS, loadTracks throws away the metadata. Sequential, so neither the app's
    // in-flight dedup nor any parallelisation could collapse them; only reuse can.
    //
    // WHY A HANDOFF AND NOT A CACHE. A short-TTL cache keyed on album id was the obvious shape and is the
    // wrong one here:
    //   the payload is a whole album, metadata plus every song - tens of KB for a 12-track album, around a
    //     megabyte for a large compilation - so holding several during a browse is a real cost for a
    //     one-shot benefit;
    //   the app cannot tell this client "that was a refresh", so any TTL long enough to feel safe is long
    //     enough that a user who opens an album, sees something wrong and pulls to refresh gets the cached
    //     payload handed straight back and nothing appears to happen.
    // Consumption solves both. loadAlbum stores; loadTracks takes AND CLEARS. So the slot is empty between
    // opens, a refresh always refetches (loadTracks cleared it, so the next loadAlbum has nothing to reuse),
    // and at most one payload is ever held.
    //
    // The TTL is only a safety net for the NEVER-CONSUMED case: the user opens the header and backs out
    // before loadTracks runs, or the app's durable album cache short-circuits loadTracks entirely (it does,
    // within 24h - loadAlbum still runs on every open because loadMedia never short-circuits). Then nothing
    // takes the slot and a payload would sit there indefinitely. 30s is two orders of magnitude longer than
    // the gap it must span, so it never interferes with the handoff; it could be 10 or 60 without changing
    // behaviour, which is the point - consumption does the work, not expiry.
    //
    // Keyed on id AND show-ness because those pick different endpoints. Two albums opened in quick
    // succession: the second loadAlbum overwrites the slot, the first loadTracks finds a key mismatch and
    // fetches. Degrades to today's behaviour; it can never serve the wrong album's payload.
    private data class Handoff(val key: String, val atMs: Long, val results: JsonObject)

    private val handoff = AtomicReference<Handoff?>(null)

    private fun handoffKey(album: Album) = "${album.type == Album.Type.Show}:${album.id}"

    // CAS rather than getAndSet: a mismatched slot belongs to a DIFFERENT album that may still be about to
    // consume it, so a failed match must leave it alone rather than discard someone else's payload.
    private fun takeHandoff(album: Album): JsonObject? {
        val held = handoff.get() ?: return null
        if (held.key != handoffKey(album)) return null
        if (System.currentTimeMillis() - held.atMs > HANDOFF_TTL_MS) return null
        return if (handoff.compareAndSet(held, null)) held.results else null
    }

    suspend fun loadAlbum(album: Album): Album {
        deezerExtension.handleArlExpiration()
        val resultsObject = results(album)
        // Always fetched, never read from the slot - that is what keeps pull-to-refresh honest.
        handoff.set(Handoff(handoffKey(album), System.currentTimeMillis(), resultsObject))
        return parser.run { if (album.type == Album.Type.Show) resultsObject.toShow() else resultsObject.toAlbum() }
    }

    fun loadTracks(album: Album): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        // Reuses the payload loadAlbum just fetched when this is the paired call; falls through to a real
        // request otherwise (a retry, a re-invoked PagedData.Single, or an unpaired loadTracks), which is
        // the correct behaviour in each of those cases.
        val resultsObject = takeHandoff(album) ?: results(album)
        if (album.type == Album.Type.Show) {
            // Same guard as the album branch: a show with no episodes, or no bookmarked episodes,
            // returns a results object without EPISODES.data — yield empty rather than NPE on !!.
            val dataArray = resultsObject["EPISODES"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            val bookmarkMap = api.getBookmarkedEpisodes()["results"]?.jsonObject?.get("data")?.jsonArray.orEmpty().associate { ep ->
                ep.jsonObject["EPISODE_ID"]?.jsonPrimitive?.content to
                        ep.jsonObject["OFFSET"]?.jsonPrimitive?.content?.toLongOrNull()
            }

            dataArray.map { episode ->
                parser.run { episode.jsonObject.toEpisode(bookmarkMap) }
            }.reversed()
        } else {
            // Some albums (region-restricted / unavailable / placeholder) return a valid results
            // object with no SONGS (or SONGS without data). Guard the chain instead of !! so those
            // just yield an empty track list rather than crashing album load with an NPE.
            val dataArray = resultsObject["SONGS"]?.jsonObject?.get("data")?.jsonArray.orEmpty()
            dataArray.mapIndexed { index, song ->
                val currentTrack = parser.run { song.jsonObject.toTrack() }
                val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.jsonObject?.toTrack() }
                currentTrack.copy(
                    extras = currentTrack.extras + mapOf(
                        "NEXT" to nextTrack?.id.orEmpty(),
                        "album_id" to album.id
                    )
                )
            }
        }
    }.toFeed()
}