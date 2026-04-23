package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class DeezerRadio(private val deezerApi: DeezerApi) {

    suspend fun mix(id: String): JsonObject {
        return deezerApi.callApi(
            method = "song.getSearchTrackMix",
            paramsBuilder = {
                put("sng_id", id)
                put("start_with_input_track", false)
            }
        )
    }

    suspend fun mixArtist(id: String): JsonObject {
        return deezerApi.callApi(
            method = "smart.getSmartRadio",
            paramsBuilder = {
                put("art_id", id)
            }
        )
    }

    suspend fun radio(trackId: String, artistId: String): JsonObject {
        return deezerApi.callApi(
            method = "radio.getUpNext",
            paramsBuilder = {
                put("art_id", artistId)
                put("limit", 10)
                put("sng_id", trackId)
            }
        )
    }

    suspend fun flow(id: String, userId: String): JsonObject {
        return deezerApi.callApi(
            method = "radio.getUserRadio",
            paramsBuilder = {
                if (id != "default") {
                    put("config_id", id)
                }
                put("user_id", userId)
            }
        )
    }
}