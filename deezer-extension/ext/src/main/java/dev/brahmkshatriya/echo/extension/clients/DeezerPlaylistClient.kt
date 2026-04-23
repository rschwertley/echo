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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DeezerPlaylistClient(private val deezerExtension: DeezerExtension, private val api: DeezerApi, private val parser: DeezerParser) {

    fun getShelves(playlist: Playlist): Feed<Shelf> = PagedData.Single {
        /*DeezerExtension().handleArlExpiration()
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        val songsObject = resultsObject["SONGS"]!!.jsonObject
        val dataArray = songsObject["data"]?.jsonArray ?: JsonArray(emptyList())
        val data = dataArray.mapNotNull { song ->
            parser.run {
                song.jsonObject.toShelfItemsList(name = "")
            }
        }
        //data*/
        emptyList<Shelf>()
    }.toFeed()

    suspend fun loadPlaylist(playlist: Playlist): Playlist {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.playlist(playlist)
        val resultsObject = jsonObject["results"]!!.jsonObject
        return parser.run { resultsObject.toPlaylist() }
    }

    fun loadTracks(playlist: Playlist): Feed<Track> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.playlist(playlist)
        val dataArray = jsonObject["results"]!!.jsonObject["SONGS"]!!.jsonObject["data"]!!.jsonArray
        dataArray.mapIndexed { index, song ->
            val currentTrack = parser.run { song.jsonObject.toTrack() }
            val nextTrack = parser.run { dataArray.getOrNull(index + 1)?.jsonObject?.toTrack() }
            currentTrack.copy(
                extras = currentTrack.extras + mapOf(
                    "NEXT" to nextTrack?.id.orEmpty(),
                    "playlist_id" to playlist.id
                )
            )
        }
    }.toFeed()
}