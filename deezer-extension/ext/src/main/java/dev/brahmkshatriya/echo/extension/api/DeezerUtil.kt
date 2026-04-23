package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

class DeezerUtil(private val deezerApi: DeezerApi) {

    suspend fun updateCountry(country: String) {
        deezerApi.callApi(
            method = "user.updateRecommendationCountry",
            paramsBuilder = {
                put("RECOMMENDATION_COUNTRY", country)
            }
        )
    }

    suspend fun log(track: Track, userId: String) {
        val id = track.id
        val next = track.extras["NEXT"]
        val ctxtT: String
        val ctxtId = when {
            !track.extras["album_id"].isNullOrEmpty() -> {
                ctxtT = "album_page"
                track.extras["album_id"]
            }
            !track.extras["playlist_id"].isNullOrEmpty() -> {
                ctxtT = "playlist_page"
                track.extras["playlist_id"]
            }
            !track.extras["artist_id"].isNullOrEmpty() -> {
                ctxtT = "up_next_artist"
                track.extras["artist_id"]
            }
            !track.extras["user_id"].isNullOrEmpty() -> {
                ctxtT = "dynamic_page_user_radio"
                userId
            }
            else -> {
                ctxtT = ""
                ""
            }
        }
        deezerApi.callApi(
            method = "log.listen",
            paramsBuilder = {
                putJsonObject("next_media") {
                    putJsonObject("media") {
                        put("id", next)
                        put("type", "song")
                    }
                }
                putJsonObject("params") {
                    putJsonObject("ctxt") {
                        put("id", ctxtId)
                        put("t", ctxtT)
                    }
                    putJsonObject("dev") {
                        put("t", 0)
                        put("v", "10020240822130111")
                    }
                    put("is_shuffle", false)
                    putJsonArray("ls") {}
                    put("lt", 1)
                    putJsonObject("media") {
                        put("format", "MP3_128")
                        put("id", id)
                        put("type", "song")
                    }
                    putJsonObject("payload") {}
                    putJsonObject("stat") {
                        put("pause", 0)
                        put("seek", 0)
                        put("sync", 0)
                    }
                    put("stream_id", UUID.randomUUID().toString())
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("ts_listen", System.currentTimeMillis() / 1000)
                    put("type", 0)
                }
            }
        )
    }
}