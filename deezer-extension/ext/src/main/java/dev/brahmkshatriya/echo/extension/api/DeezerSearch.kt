package dev.brahmkshatriya.echo.extension.api

import dev.brahmkshatriya.echo.extension.DeezerApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class DeezerSearch(private val deezerApi: DeezerApi) {

    suspend fun search(query: String): JsonObject {
        return deezerApi.callApi(
            method = "deezer.pageSearch",
            paramsBuilder = {
                put("nb", 128)
                put("query", query)
                put("start", 0)
            }
        )
    }

    suspend fun searchSuggestions(query: String): JsonObject {
        return deezerApi.callApi(
            method = "search_getSuggestedQueries",
            paramsBuilder = {
                put("QUERY", query)
            }
        )
    }

    suspend fun setSearchHistory(query: String) {
        deezerApi.callApi(
            method = "user.addEntryInSearchHistory",
            paramsBuilder = {
                putJsonObject("ENTRY") {
                    put("query", query)
                    put("type", "query")
                }
            }
        )
    }

    suspend fun getSearchHistory(): JsonObject {
        return deezerApi.callApi(
            method = "deezer.userMenu"
        )
    }

    suspend fun deleteSearchHistory(userId: String) {
        deezerApi.callApi(
            method = "user.clearSearchHistory",
            paramsBuilder = {
                put("USER_ID", userId)
            }
        )
    }
}