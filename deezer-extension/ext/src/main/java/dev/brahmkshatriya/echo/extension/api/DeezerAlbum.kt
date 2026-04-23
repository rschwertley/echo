package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class DeezerAlbum(private val deezerApi: DeezerApi) {

    suspend fun album(album: Album): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageAlbum",
            paramsBuilder = {
                put("alb_id", album.id)
                put("header", true)
                put("lang", deezerApi.langCode)
            }
        )
    }

    suspend fun getAlbums(userId: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageProfile",
            paramsBuilder = {
                put("user_id", userId)
                put("tab", "albums")
                put("nb", 10000)
            }
        )
    }

    suspend fun addFavoriteAlbum(id: String) {
        deezerApi.callApi(
            method = "album.addFavorite",
            paramsBuilder = {
                put("ALB_ID", id)
            }
        )
    }

    suspend fun removeFavoriteAlbum(id: String) {
        deezerApi.callApi(
            method = "album.deleteFavorite",
            paramsBuilder = {
                put("ALB_ID", id)
            }
        )
    }
}