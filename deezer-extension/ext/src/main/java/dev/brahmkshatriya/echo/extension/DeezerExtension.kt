package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingCategory
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingSlider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultCountryIndex
import dev.brahmkshatriya.echo.extension.DeezerCountries.getDefaultLanguageIndex
import dev.brahmkshatriya.echo.extension.clients.DeezerAlbumClient
import dev.brahmkshatriya.echo.extension.clients.DeezerArtistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerHomeFeedClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLibraryClient
import dev.brahmkshatriya.echo.extension.clients.DeezerLyricsClient
import dev.brahmkshatriya.echo.extension.clients.DeezerPlaylistClient
import dev.brahmkshatriya.echo.extension.clients.DeezerRadioClient
import dev.brahmkshatriya.echo.extension.clients.DeezerSearchClient
import dev.brahmkshatriya.echo.extension.clients.DeezerTrackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class DeezerExtension : HomeFeedClient, TrackClient, LikeClient, RadioClient,
    SearchFeedClient, QuickSearchClient,AlbumClient, ArtistClient, FollowClient, PlaylistClient, LyricsClient, ShareClient,
    TrackerClient, TrackerMarkClient, LoginClient.WebView, LoginClient.CustomInput,
    LibraryFeedClient, PlaylistEditClient, SaveClient {

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val session by lazy { DeezerSession.getInstance() }
    private val api by lazy { DeezerApi(session) }
    private val parser by lazy { DeezerParser(session) }
    private var likedTrackIds: HashSet<String>? = null

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                "Use Proxy",
                "proxy",
                "Use proxy to prevent GEO-Blocking",
                mutableListOf("No Proxy", "UK 1", "UK 2", "RU 1", "RU 2", "MD"),
                mutableListOf("", "uk1.proxy.murglar.app", "uk2.proxy.murglar.app", "ru1.proxy.murglar.app", "ru2.proxy.murglar.app", "md.proxy.murglar.app"),
                0
            ),
            SettingSwitch(
                "Enable Logging",
                "log",
                "Enables logging to deezer",
                true
            ),
            SettingSwitch(
                "Enable Search History",
                "history",
                "Enables the search history",
                true
            ),
            SettingCategory(
                "Quality",
                "quality",
                mutableListOf(
                    SettingList(
                        "Wi-Fi Streaming Quality",
                        "unmetered_stream_quality",
                        "Audio quality used on Wi-Fi or unmetered connections",
                        mutableListOf("High (FLAC)", "Medium (320kbps)", "Low (128kbps)", "Auto (Global App Setting)"),
                        mutableListOf("highest", "medium", "lowest", "off"),
                        3
                    ),
                    SettingList(
                        "Mobile Data Streaming Quality",
                        "stream_quality",
                        "Audio quality used on mobile data connections",
                        mutableListOf("High (FLAC)", "Medium (320kbps)", "Low (128kbps)", "Auto (Global App Setting)"),
                        mutableListOf("highest", "medium", "lowest", "off"),
                        3
                    ),
                    SettingSlider(
                        "Image Quality",
                        "image_quality",
                        "Choose your preferred image quality (Can impact loading times)",
                        240,
                        120,
                        1920,
                        120
                    )
                )
            ),
            SettingCategory(
                "Language & Country",
                "langcount",
                mutableListOf(
                    SettingList(
                        "Language",
                        "lang",
                        "Choose your preferred language for loaded stuff",
                        DeezerCountries.languages.map { it.name },
                        DeezerCountries.languages.map { it.code },
                        getDefaultLanguageIndex(session.settings)
                    ),
                    SettingList(
                        "Country",
                        "country",
                        "Choose your preferred country for browse recommendations",
                        DeezerCountries.countries.map { it.name },
                        DeezerCountries.countries.map { it.code },
                        getDefaultCountryIndex(session.settings)
                    )
                )
            ),
            SettingCategory(
                "Appearance",
                "appearance",
                mutableListOf(
                    SettingList(
                        "Shelf Type",
                        "shelf",
                        "Choose your preferred shelf type",
                        mutableListOf("Grid", "Linear"),
                        mutableListOf("grid", "linear"),
                        0
                    )
                )
            )
        )
    }

    override fun setSettings(settings: Settings) {
        session.settings = settings
    }

    override suspend fun onExtensionSelected() {
        session.settings?.let { setSettings(it) }
        runCatching { handleArlExpiration() }
    }

    //<============= HomeTab =============>

    private val deezerHomeFeedClient by lazy { DeezerHomeFeedClient(this, api, parser) }

    override suspend fun loadHomeFeed(): Feed<Shelf> = deezerHomeFeedClient.loadHomeFeed(shelf)

    //<============= Library =============>

    private val deezerLibraryClient by lazy { DeezerLibraryClient(this, api, parser) }

    override suspend fun loadLibraryFeed(): Feed<Shelf> = deezerLibraryClient.loadLibraryFeed()

    override suspend fun addTracksToPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        index: Int,
        new: List<Track>
    ) {
        handleArlExpiration()
        api.addToPlaylist(playlist, new)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        indexes: List<Int>
    ) {
        handleArlExpiration()
        api.removeFromPlaylist(playlist, tracks, indexes)
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        handleArlExpiration()
        val jsonObject = api.createPlaylist(title, description)
        val id = jsonObject["results"]?.jsonPrimitive?.content.orEmpty()
        val playlist = Playlist(
            id = id,
            title = title,
            description = description,
            isEditable = true
        )
        return playlist
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        handleArlExpiration()
        api.deletePlaylist(playlist.id)
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist,
        title: String,
        description: String?
    ) {
        handleArlExpiration()
        api.updatePlaylist(playlist.id, title, description)
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        handleArlExpiration()
        when (item) {
            is Track -> {
                if (shouldLike) {
                    likedTrackIds?.add(item.id)
                    api.addFavoriteTrack(item.id)
                } else {
                    likedTrackIds?.remove(item.id)
                    api.removeFavoriteTrack(item.id)
                }
            }
            else -> {}
        }
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        if (item !is Track) return false
        val ids = likedTrackIds ?: fetchLikedTrackIds().also { likedTrackIds = it }
        return item.id in ids
    }

    private suspend fun fetchLikedTrackIds(): HashSet<String> {
        val dataArray = runCatching {
            api.getTracks()["results"]?.jsonObject?.get("data")?.jsonArray
        }.getOrNull() ?: return hashSetOf()
        return dataArray.mapNotNull {
            runCatching { it.jsonObject["SNG_ID"]?.jsonPrimitive?.content }.getOrNull()
        }.toHashSet()
    }

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        handleArlExpiration()
        val playlistList = mutableListOf<Pair<Playlist, Boolean>>()
        val jsonObject = api.getPlaylists()
        val resultObject = jsonObject["results"]!!.jsonObject
        val tabObject = resultObject["TAB"]!!.jsonObject
        val playlistObject = tabObject["playlists"]!!.jsonObject
        val dataArray = playlistObject["data"]!!.jsonArray
        dataArray.forEach {
            val playlist = parser.run { it.jsonObject.toPlaylist() }
            if (playlist.isEditable) {
                playlistList.add(Pair(playlist, false))
            }
        }
        return playlistList
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist,
        tracks: List<Track>,
        fromIndex: Int,
        toIndex: Int
    ) {
        handleArlExpiration()
        val idArray = tracks.map { it.id }.toMutableList()
        idArray.add(toIndex, idArray.removeAt(fromIndex))
        api.updatePlaylistOrder(playlist.id, idArray)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        return when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    getIsItemSaved(api::getShows, "SHOW_ID", item.id)
                } else {
                    getIsItemSaved(api::getAlbums, "ALB_ID", item.id)
                }
            }

            is Playlist -> {
                getIsItemSaved(api::getPlaylists, "PLAYLIST_ID", item.id)
            }

            is Track -> {
                getIsItemSaved(api::getTracks, "SNG_ID", item.id)
            }

            else -> false
        }
    }

    private suspend fun getIsItemSaved(
        getItems: suspend () -> JsonObject,
        idKey: String,
        itemId: String
    ): Boolean {
        val dataObject = getItems()["results"]?.jsonObject
        val dataArray = if (idKey == "SNG_ID") {
            dataObject?.get("data")?.jsonArray ?: return false

        } else {
            dataObject?.get("TAB")?.jsonObject
                ?.values?.firstOrNull()?.jsonObject
                ?.get("data")?.jsonArray ?: return false
        }
        return dataArray.any { item ->
            val id = item.jsonObject[idKey]?.jsonPrimitive?.content
            id == itemId
        }
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        handleArlExpiration()
        when (item) {
            is Album -> {
                if (item.type == Album.Type.Show) {
                    if (shouldSave) api.addFavoriteShow(item.id) else api.removeFavoriteShow(
                        item.id
                    )
                } else {
                    if (shouldSave) api.addFavoriteAlbum(item.id) else api.removeFavoriteAlbum(
                        item.id
                    )
                }

            }

            is Playlist -> {
                if (shouldSave) api.addFavoritePlaylist(item.id) else api.removeFavoritePlaylist(item.id)
            }

            is Track -> {
                if (shouldSave) api.addFavoriteTrack(item.id) else api.removeFavoriteTrack(item.id)
            }

            else -> {}
        }
    }

    //<============= Search =============>

    private val deezerSearchClient by lazy { DeezerSearchClient(this, api, extensionScope, history, parser) }

    override suspend fun quickSearch(query: String): List<QuickSearchItem.Query> = deezerSearchClient.quickSearch(query)

    override suspend fun deleteQuickSearch(item: QuickSearchItem) = api.deleteSearchHistory()

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = deezerSearchClient.loadSearchFeed(query, shelf)

    /**
     * ⚠️ A MISSING SECTION TITLE IS NOT A REASON TO DROP THE SECTION. Until 2026-09-07 this required
     * `section.title` and returned null without it, which discarded the entire page: the device capture of
     * channels/module/46b377f1 shows ONE section, `title=<null>`, layout=grid, holding 25 playlist items
     * of which all 25 parse with the existing code. The items were never the problem — the header was.
     * That is also why the four /channels/module/<uuid> Home rows opened to nothing.
     *
     * TITLE FALLBACK, in order, each level giving something the previous one cannot:
     *   1. section["title"] — per-section, the only level that can distinguish two sections of one page.
     *      Null on module pages; present on multi-section channel pages, which is who it is for.
     *   2. results["title"] — the PAGE title (resultsKeys carries one even when the section does not).
     *      For a module page this is the label Deezer is showing today.
     *   3. "" — render with NO header rather than dropping. FeedType emits a Header for every Shelf.Lists,
     *      and HeaderViewHolder does `title.isVisible = feed.title.isNotEmpty()`, so a blank title is an
     *      invisible header row followed by the items. Content survives; only decoration is lost.
     * Deliberately NOT a level: the caller's own shelf title from Home. It is the most stable string we
     * hold, but channelFeed is reached through a `more` Feed lambda that does not carry it, so plumbing it
     * through would mean threading a display string down two layers for a case level 2 already covers.
     * Worth revisiting only if a module page turns up with no page title either.
     *
     * ⚠️ THE ID DOES NOT COME FROM THE TITLE. section_id, else module_id, else the target. Module labels
     * ROTATE — 46b377f1 was "Summer in slow-mo" days before it was "Hello, sunshine" — so a title-derived
     * id would change under the same content, and two untitled sections sharing a page title would collide
     * on one cache key in Cached.getFeedShelf.
     */
    suspend fun channelFeed(target: String): List<Shelf> {
        val jsonObject = api.page(target.substringAfter("/"))
        // ⚠️ THESE TWO WERE `!!` UNTIL 2026-09-07 AND THE SECOND ONE CRASHED THE APP. `target` is not one
        // endpoint, it is four families, and /artist/<id> is an ARTIST endpoint with no "sections" key at
        // all — the retraced NPE (build 1077 mapping, pg_map_id 9d7725eb…, frame nm0.a:127). Returning an
        // empty list instead is what makes an unhandled family DEGRADE rather than throw: the caller's
        // `more` treats an empty fetch as "use the row's own items", so the arrow behaves exactly as it
        // does with the fetch branch off. See the note at DeezerParser.toShelfItemsList's `more`.
        // ⚠️ PERMANENT, NOT A TEMPORARY DIAGNOSTIC — same carve-out as the two DROP lines, and for the
        // same reason. The `more` fallback in DeezerParser.toShelfItemsList makes a failed fetch look
        // EXACTLY like a successful one (the row's own items appear either way), which is right for users
        // and blinding for us: a family that quietly degrades is indistinguishable from one that works.
        // A silent degradation is how "Made for you" went unnoticed for months. These fire ONLY on the
        // failure path, so a working family is silent and a healthy session prints nothing.
        val channelPageResults = jsonObject["results"] as? JsonObject ?: run {
            println("GladixDeezer FALLBACK target=$target reason=no-results rootKeys=${jsonObject.keys}")
            return emptyList()
        }
        val channelSections = channelPageResults["sections"] as? JsonArray ?: run {
            // keys= is the field that earns this line: it names what the page DID return, which is the
            // whole question for /channels/new and for any family nobody has captured.
            println(
                "GladixDeezer FALLBACK target=$target reason=no-sections " +
                    "resultsKeys=${channelPageResults.keys}"
            )
            return emptyList()
        }
        val pageTitle = channelPageResults["title"]?.jsonPrimitive?.contentOrNull
        return supervisorScope {
            channelSections.map { section ->
                async(Dispatchers.Default) {
                    parser.run {
                        val obj = section.jsonObject
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull
                            ?: pageTitle.orEmpty()
                        val id = obj["section_id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["module_id"]?.jsonPrimitive?.contentOrNull
                            ?: target
                        // Recursive by design: a channel page's rows get fetching arrows too when they
                        // carry a target. See toShelfItemsList's depth note — bounded by Deezer's graph,
                        // not by us.
                        section.toShelfItemsList(title, id) { t -> channelFeed(t) }
                    }
                }
            }.awaitAll().filterNotNull().also { shelves ->
                // Third distinct cause: the page WAS section-shaped and still yielded nothing, i.e. the
                // sections parsed to zero items. Distinguishing it from the two above is what separates
                // "we asked the wrong endpoint" from "we asked the right one and cannot read it".
                if (shelves.isEmpty() && channelSections.isNotEmpty()) println(
                    "GladixDeezer FALLBACK target=$target reason=no-section-parsed " +
                        "sections=${channelSections.size}"
                )
            }
        }
    }

    //<============= Play =============>

    private val deezerTrackClient by lazy { DeezerTrackClient(this, api, parser) }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media = deezerTrackClient.loadStreamableMedia(streamable)

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = deezerTrackClient.loadTrack(track)

    override suspend fun loadFeed(track: Track): Feed<Shelf> = loadFeed(track.artists.first())

    //<============= Radio =============>

    private val deezerRadioClient by lazy { DeezerRadioClient(api, parser) }

    override suspend fun loadTracks(radio: Radio): Feed<Track> = deezerRadioClient.loadTracks(radio)

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio = deezerRadioClient.radio(item, context)

    override suspend fun loadRadio(radio: Radio): Radio  = radio

    //<============= Lyrics =============>

    private val deezerLyricsClient by lazy { DeezerLyricsClient(api) }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics = lyrics

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> = deezerLyricsClient.searchTrackLyrics(track)

    //<============= Album =============>

    private val deezerAlbumClient by lazy { DeezerAlbumClient(this, api, parser) }

    override suspend fun loadFeed(album: Album): Feed<Shelf> = loadFeed(album.artists.first())

    override suspend fun loadAlbum(album: Album): Album = deezerAlbumClient.loadAlbum(album)

    override suspend fun loadTracks(album: Album): Feed<Track> = deezerAlbumClient.loadTracks(album)

    //<============= Playlist =============>

    private val deezerPlaylistClient by lazy { DeezerPlaylistClient(this, api, parser) }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = deezerPlaylistClient.getShelves(playlist)

    override suspend fun loadPlaylist(playlist: Playlist): Playlist = deezerPlaylistClient.loadPlaylist(playlist)

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> = deezerPlaylistClient.loadTracks(playlist)

    //<============= Artist =============>

    private val deezerArtistClient by lazy { DeezerArtistClient(this, api, parser) }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> = deezerArtistClient.getShelves(artist)

    override suspend fun loadArtist(artist: Artist): Artist = deezerArtistClient.loadArtist(artist)

    override suspend fun isFollowing(item: EchoMediaItem): Boolean = deezerArtistClient.isFollowing(item)

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? = deezerArtistClient.getFollowersCount(item)

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        if (shouldFollow) api.followArtist(item.id) else api.unfollowArtist(item.id)
    }

    //<============= Login =============>

    override suspend fun getCurrentUser(): User {
        val userList = api.makeUser()
        return userList.firstOrNull() ?: throw Exception("Login failed: could not retrieve user after authentication")
    }

    override val webViewRequest = object : WebViewRequest.Headers<List<User>> {
        override suspend fun onStop(requests: List<NetworkRequest>): List<User> {
            val request = requests.firstOrNull() ?: throw Exception("Login failed: no auth request intercepted — try logging in again")
            val data = request.headers
            val arl = extractCookieValue(data, "arl")
            val sid = extractCookieValue(data, "sid")
            if (arl != null && sid != null) {
                session.updateCredentials(arl = arl, sid = sid)
                val credJObj = api.decodeJson(request.body?.decodeToString()!!)
                val mail = credJObj["MAIL"]?.jsonPrimitive?.content!!
                val pass = credJObj["PASSWORD"]?.jsonPrimitive?.content!!
                session.updateCredentials(
                    email = mail,
                    pass = pass
                )
                return api.makeUser(mail, pass)
            } else if (data.isEmpty()) {
                throw Exception("Ignore this")
            } else {
                throw Exception("Failed to retrieve ARL and SID from cookies")
            }
        }

        override val initialUrl = "https://www.deezer.com/login?redirect_type=page&redirect_link=%2Faccount%2F".toGetRequest(
            mapOf(
                Pair(
                    "user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
                )
            )
        )

        override val interceptUrlRegex = "https://www\\.deezer\\.com/ajax/gw-light\\.php\\?method=deezer_userAuth.*".toRegex()

        override val stopUrlRegex = "https://www\\.deezer\\.com/account/.*".toRegex()

        private fun extractCookieValue(data: Map<String,String>, key: String): String? {
            return data["cookie"]?.substringAfter("$key=")?.substringBefore(";").takeIf { it?.isNotEmpty() == true }
        }
    }

    override val forms: List<LoginClient.Form> = listOf(
        LoginClient.Form(
            key = "userPass",
            label = "E-Mail and Password",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Email,
                    key = "email",
                    label = "E-Mail",
                    isRequired = true,
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "pass",
                    label = "Password",
                    isRequired = true
                )
            )
        ),
        LoginClient.Form(
            key = "manual",
            label = "ARL",
            icon = LoginClient.InputField.Type.Misc,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Misc,
                    key = "arl",
                    label = "ARL",
                    isRequired = false,
                )

            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        if(data["email"] != null && data["pass"] != null) {
            val email = data["email"]!!
            val password = data["pass"]!!

            session.updateCredentials(email = email, pass = password)

            api.getArlByEmail(email, password, 3)
            val userList = api.makeUser(email, password)
            return userList
        } else {
            session.updateCredentials(arl = data["arl"] ?: "")
            api.getSid()
            val userList = api.makeUser()
            userList.firstOrNull()?.extras?.let { extras ->
                session.updateCredentials(
                    token = extras["token"] ?: "",
                    userId = extras["user_id"] ?: "",
                    licenseToken = extras["license_token"] ?: "",
                    sid = extras["sid"] ?: ""
                )
            }
            return userList
        }
    }

    override fun setLoginUser(user: User?) {
        likedTrackIds = null
        if (user != null) {
            session.updateCredentials(
                arl = user.extras["arl"] ?: "",
                sid = user.extras["sid"] ?: "",
                token = user.extras["token"] ?: "",
                userId = user.extras["user_id"] ?: "",
                licenseToken = user.extras["license_token"] ?: "",
                email = user.extras["email"] ?: "",
                pass = user.extras["pass"] ?: ""
            )
        } else {
            session.updateCredentials(
                arl = "",
                sid = "",
                token = "",
                userId = "",
                licenseToken = "",
                email = "",
                pass = ""
            )
        }
    }

    //<============= Share =============>

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Track -> "https://www.deezer.com/track/${item.id}"
            is Artist -> "https://www.deezer.com/artist/${item.id}"
            //is EchoMediaItem.Profile.UserItem -> "https://www.deezer.com/profile/${item.id}"
            is Album -> "https://www.deezer.com/album/${item.id}"
            is Playlist -> "https://www.deezer.com/playlist/${item.id}"
            is Radio -> TODO("Does not exist")
        }
    }

    //<============= Tracking =============>

    override suspend fun onTrackChanged(details: TrackDetails?) {}

    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long = 30000L

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        if (log) api.log(details.track)
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        val track = details?.track
        if (track?.type == Track.Type.Podcast && !isPlaying) {
            api.bookmarkEpisode(
                track.id,
                details.currentPosition.div(1000),
                details.totalDuration?.div(1000)?.toDouble() ?: 0.0
            )
        }
    }

    //<============= Utils =============>

    suspend fun handleArlExpiration() {
        val creds = session.credentials
        val isArlExpired = session.arlExpired || creds.arl.isEmpty()
        if (isArlExpired || creds.sid.isEmpty() || creds.token.isEmpty()) {
            if (creds.email.isNotEmpty() && creds.pass.isNotEmpty()) {
                api.makeUser()
            } else if (isArlExpired) {
                throw ClientException.LoginRequired()
            } else {
                runCatching { api.makeUser() }
            }
        }
    }

    private val shelf: String get() = session.settings?.getString("shelf") ?: DEFAULT_TYPE
    private val log: Boolean get() = session.settings?.getBoolean("log") == true
    private val history: Boolean get() = session.settings?.getBoolean("history") != false

    companion object {
        private const val DEFAULT_TYPE = "grid"
    }
}