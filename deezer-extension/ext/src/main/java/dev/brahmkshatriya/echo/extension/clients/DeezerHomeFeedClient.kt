package dev.brahmkshatriya.echo.extension.clients

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.extension.DeezerApi
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

class DeezerHomeFeedClient(
    private val deezerExtension: DeezerExtension,
    private val api: DeezerApi,
    private val parser: DeezerParser
) {

    fun loadHomeFeed(shelf: String): Feed<Shelf> = PagedData.Single {
        deezerExtension.handleArlExpiration()
        val jsonObject = api.page("home")
        logSections("home", jsonObject, parser)

        val homePageResults = jsonObject["results"]?.jsonObject ?: JsonObject(emptyMap())
        val homeSections = homePageResults["sections"]?.jsonArray ?: JsonArray(emptyList())

        supervisorScope {
            // TEMPORARY PROBE - DELETE WITH DeezerParser's firstItem= DUMP AND RAW_ITEM_LOG_CAP.
            // Runs concurrently with the section parse, but supervisorScope joins its children, so the
            // FIRST Home load of each process still waits for one extra page.get round-trip. That cost is
            // why it is one-shot and why it is temporary; it is not a permanent diagnostic like the
            // titleless-section DROP line below.
            val probe = async(dispatcher) { probeOnce(api, parser, jsonObject) }
            val shelves = homeSections.mapNotNull { section ->
                val obj = section.asObjectOrNull() ?: return@mapNotNull null
                val id = obj.optString("module_id") ?: return@mapNotNull null
                // ⚠️ PERMANENT — NOT A TEMPORARY DIAGNOSTIC. DO NOT STRIP.
                // June's blanket "remove all GladixDeezer printlns before a release build" rule is about
                // trace spam; it does NOT apply to this line or to its twin in DeezerExtension.channelFeed.
                // This fires ONLY on a failure — a section Deezer sent that we then discard — so it costs
                // nothing on a healthy load and is silent in every normal session.
                //
                // WHY IT EARNS PERMANENCE: a section with no title is dropped here and never reaches the
                // parser, so the row vanishes from Home with no signal anywhere. That silence is how "Made
                // for you" went unnoticed for months — it was in the response the whole time and simply
                // never rendered. This line turns the next Deezer shape change into an immediate,
                // greppable symptom instead of a row nobody notices is missing.
                // module_id is printed because it is the only stable handle on a titleless section.
                val title = obj.optString("title") ?: run {
                    println("GladixDeezer DROP section=<no-title> reason=missing-title module_id=$id")
                    return@mapNotNull null
                }
                val hasChannelItems = parser.run { obj.hasChannelItems() }

                when {
                    id in CATEGORY_MODULE_ID || hasChannelItems -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                section.toShelfCategoryList(title, shelf) { target ->
                                    deezerExtension.channelFeed(target)
                                }
                            }
                        }.getOrNull()
                    }
                    else -> async(dispatcher) {
                        runCatching {
                            parser.run {
                                // Same resolver the category path uses, so a carousel's "see all" and a
                                // channel tile reach a page the same way. Null target → re-wrap fallback.
                                section.toShelfItemsList(title) { target ->
                                    deezerExtension.channelFeed(target)
                                }
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            // Awaited rather than dropped so a probe that somehow escapes its runCatching is visible here
            // instead of being swallowed by the supervisor. Its body cannot throw except on cancellation.
            probe.await()
            shelves
        }
    }.toFeed()

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.optString(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        private fun logSections(label: String, page: JsonObject, parser: DeezerParser) {
            val sections = page["results"]?.jsonObject?.get("sections")?.jsonArray ?: JsonArray(emptyList())
            val summary = sections.joinToString(", ") { section ->
                val obj = section.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "?"
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull ?: "?"
                val moduleId = obj["module_id"]?.jsonPrimitive?.contentOrNull ?: "?"
                // The section-level "see all" page path, e.g. /channels/module/<uuid> — the same shape
                // api.page(target.substringAfter("/")) consumes. "?" means the section has none and its
                // arrow can only re-wrap. Confirmed populated on Search 2026-09-04; logged here to settle
                // whether Home's sections carry them too.
                val target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "?"
                val items = obj["items"]?.jsonArray ?: JsonArray(emptyList())
                // Same __TYPE__ extraction as DeezerParser.hasChannelItems() so the logged breakdown
                // reflects exactly the field that drives Home routing — but EVERY item is now counted under
                // some bucket, never dropped.
                //
                // ⚠️ THE INVARIANT IS THE POINT, NOT THE EXTRA FIELD: `sum(types) == items`. A reader can
                // check it at a glance, and it FAILS LOUDLY if a future item shape escapes both lookups.
                // This is the fourth extension of this line — it began as title/layout, gained
                // module_id/target, then items=N, then `target` on Home to confirm it there. Each closed a
                // blind spot the previous version had, and each was still merely WIDER. This one is the
                // first that is SELF-CHECKING. Keep that property: if you add a bucket, make sure it still
                // totals.
                //
                // ⚠️ WHAT IT FIXES, AND WHY IT WENT UNSEEN FOR WEEKS. The old form was
                //   items.mapNotNull { (it as? JsonObject)?.unwrap()?.str("__TYPE__") }
                // and mapNotNull SILENTLY DISCARDED every item with no `data.__TYPE__` — which is exactly the
                // smarttracklist shape, since those carry their type at the OUTER level. The 2026-09-07 Home
                // capture read `New releases for you/horizontal-grid/…/items=12/types={album:11}`: the line
                // was REPORTING the discrepancy (12 vs 11) without naming the missing item, so the one shape
                // the log existed to find was the one shape it could not show. An `outer:` bucket would have
                // surfaced it weeks ago, and would have shown that smarttracklist items are scattered
                // through ordinary rows rather than confined to "Made for you".
                //
                // ⚠️ PATTERN, STATED PLAINLY BECAUSE IT HAS NOW COST FOUR TIMES: mapNotNull over a
                // HETEROGENEOUS collection discards precisely the cases you most need to see. The other
                // members are the swallowed gateway `error` key (see DeezerApi.callApi), the titleless-section
                // DROP lines below, and — same operator, different data — Android clearing cacheDir under
                // storage pressure, which made a mapNotNull drop the item with no signal at all.
                // A COUNT-BASED DIAGNOSTIC MUST PUBLISH AN INVARIANT THAT FAILS LOUDLY WHEN IT BREAKS.
                val typeCounts = parser.run {
                    items.map { item ->
                        val obj = item as? JsonObject
                        obj?.unwrap()?.str("__TYPE__")
                            // Outer-level type: the smarttracklist shape, and whatever else Deezer sends
                            // this way next. Prefixed so it can never be confused with a real __TYPE__.
                            ?: obj?.str("type")?.let { "outer:$it" }
                            ?: "<no-type>"
                    }.groupingBy { it }.eachCount()
                }
                val types = typeCounts.entries.joinToString(", ") { "${it.key}:${it.value}" }
                "$title/$layout/$moduleId/$target/items=${items.size}/types={$types}"
            }
            println("GladixDeezer PAGE[$label] sections: $summary")
        }

        // == TEMPORARY: /channels/module/<uuid> BODY CAPTURE ==========================================
        // (!) DELETE THIS WHOLE BLOCK, its call in loadHomeFeed, and the AtomicBoolean, once the capture is
        // in hand. It is NOT covered by the "these two printlns are permanent" carve-out above - those fire
        // only on a failure and cost nothing; this one issues a network request nobody asked for.
        //
        // WHAT IT ANSWERS: four Home rows point at /channels/module/<uuid> - "Summer in slow-mo",
        // "Playlists you will love", "Recently you have been loving", "Playlists you love". It is the
        // largest target family, and DeezerParser's disabled fetch branch records that it "came back
        // section-shaped but yielded nothing" while NOBODY HAS SEEN THE BODY.
        //
        // The "nothing behind it" reading is now REFUTED, not open: on Deezer's own app "Summer in
        // slow-mo" expands from 12 items to 32. The content exists, so channelFeed is filtering it away,
        // and this probe exists to say WHICH of the three filters does it -
        //   (1) DeezerExtension.channelFeed drops any section with no `title`,
        //   (2) toShelfItemsList reads obj()["items"] and gives up when the key is absent or not an array,
        //   (3) every item returns null from toEchoMediaItem (no data.__TYPE__ it recognises).
        // Each prints a distinguishable line below, so one capture separates them.
        //
        // The uuid is hardcoded from the 2026-09-05 Home capture ("Summer in slow-mo"). Module uuids look
        // editorial and seasonal, so this one may stop resolving; a probe that returns an error body is
        // itself an answer (it prints error=), it is just not the answer we are after.
        private val channelModuleProbed = AtomicBoolean(false)
        private const val PROBE_TARGET = "channels/module/46b377f1-fbd5-4e04-979e-177b5df21183"
        // Same cap and same reason as DeezerParser.RAW_ITEM_LOG_CAP: logcat truncates a single line near
        // 4000 chars with no marker, so a bigger number silently looks like a shorter object. Long bodies
        // are CHUNKED across lines instead of being raised, bounded by PROBE_MAX_CHUNKS so a large page
        // cannot flood the buffer and push the summary lines out of it.
        private const val PROBE_LOG_CAP = 3000
        private const val PROBE_MAX_CHUNKS = 4

        private fun JsonElement?.prim(): String? = (this as? JsonPrimitive)?.contentOrNull

        // Shape of one value, without printing it: "array[32]", "object{6}", "primitive". This is the line
        // that answers the standing prediction - that a MODULE endpoint returns one module's contents
        // rather than a page of sections, with the payload under some key other than `sections`. If that is
        // right, `sections` is absent or empty here and some sibling key is an array of about 32.
        /**
         * ⚠️ TEMPORARY — DELETE WITH THE REST OF THIS PROBE BLOCK.
         *
         * Dumps the page for ONE smarttracklist, to settle why api.page("smarttracklist/<id>") comes back
         * with sections=0 while DeezerPlaylistClient.smartTracklistTracks assumes results.sections[].items[].
         * That traversal was marked INFERRED at the time; this is the capture that confirms or replaces it.
         *
         * ⚠️ THE ID IS DISCOVERED FROM THE HOME PAYLOAD, NOT HARDCODED. Smarttracklist ids are daily-mix
         * slots and Deezer rotates its editorial labels under stable module ids (46b377f1 was "Summer in
         * slow-mo", now "Hello, sunshine"), so a literal captured days ago is the least reliable thing to
         * key on. Taking the first one Home actually returned means the dump is always of a live id.
         *
         * WHAT TO READ IN THE OUTPUT: if resultsShapes names some array other than `sections` — `data`,
         * `items`, `songs` — that key is the real payload and smartTracklistTracks' traversal is wrong.
         * If `sections` is present but empty, the id resolves and the page is genuinely sectionless, and
         * the tracks are somewhere else in results. If `error` is populated, the id is not addressable
         * this way at all and the whole page.get route for smarttracklists is the wrong idea.
         */
        private suspend fun probeSmartTracklist(api: DeezerApi, home: JsonObject) {
            runCatching {
                val sections = home["results"]?.jsonObject?.get("sections") as? JsonArray
                val id = sections?.filterIsInstance<JsonObject>()
                    ?.flatMap { it["items"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty() }
                    ?.firstOrNull { it["type"].prim() == "smarttracklist" }
                    ?.let { (it["data"] as? JsonObject)?.get("SMARTTRACKLIST_ID").prim() }
                if (id == null) {
                    println("GladixDeezer PROBE stl=<none-in-home> (no outer type=smarttracklist item)")
                    return@runCatching
                }
                // == STEP 1 OF THE SMARTTRACKLIST WORK: WHICH ID DOES THE GRAPHQL API USE? ==========
                // (!) DELETE WITH DeezerApi.probeMadeForMe. Three lines out, one line in.
                // Our Home item carries TWO candidate ids and they are different KINDS of thing:
                //   data.SMARTTRACKLIST_ID = "inspired-by-1"  — identical to data.CONFIGURATION_ID, so it
                //                                               reads as a SLOT/config name, not an instance
                //   data.ID = "6563868601.1.1125.2179.2707.20260829" — reads as an INSTANCE: it embeds what
                //                                               looks like a user id and a date, matching
                //                                               these regenerating daily (EXPIRATION_DATE)
                // DeezerParser.toSmartTracklist currently stores the SLOT form. If madeForMe returns the
                // instance form, that line changes too — do not assume the current choice is right.
                // The match= line below is the whole point: it is the gate on every later step.
                val stlItem = sections?.filterIsInstance<JsonObject>()
                    ?.flatMap { it["items"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty() }
                    ?.firstOrNull { it["type"].prim() == "smarttracklist" }
                    ?.let { it["data"] as? JsonObject }
                val homeDataId = stlItem?.get("ID").prim()
                val homeConfigId = stlItem?.get("CONFIGURATION_ID").prim()
                println(
                    "GladixDeezer STL-ID home stl=$id dataId=${homeDataId ?: "-"} " +
                        "configId=${homeConfigId ?: "-"}"
                )
                runCatching { api.probeMadeForMe() }
                    .onFailure { println("GladixDeezer STL-ID madeForMe FAILED: ${it.message}") }
                    .onSuccess { gql ->
                        val errors = gql["errors"]?.toString()?.take(300)
                        val edges = gql["data"]?.jsonObject?.get("me")?.jsonObject
                            ?.get("madeForMe")?.jsonObject?.get("edges") as? JsonArray
                        val nodes = edges?.filterIsInstance<JsonObject>()
                            ?.mapNotNull { it["node"] as? JsonObject }.orEmpty()
                        val ids = nodes.map { it["id"].prim() ?: "-" }
                        println(
                            "GladixDeezer STL-ID madeForMe n=${nodes.size} " +
                                "types=${nodes.map { it["__typename"].prim() ?: "-" }} ids=$ids " +
                                "errors=${errors ?: "-"}"
                        )
                        // The gate. `slot` -> toSmartTracklist already stores the right id and step 2 can
                        // proceed unchanged. `instance` -> it stores the wrong one and must change first.
                        // `none` -> the GraphQL API does not address our items by either id we hold; STOP,
                        // and revert the two-line outer-type fallback in DeezerParser.toEchoMediaItem
                        // rather than start guessing at id shapes.
                        val match = when {
                            id != null && ids.contains(id) -> "slot"
                            homeDataId != null && ids.contains(homeDataId) -> "instance"
                            // A PREFIXED VARIANT IS THE AMBIGUOUS CASE AND MUST NOT REPORT AS "none".
                            // The repo's fixtures use "smart:daily_mix_1", so a real id may well be
                            // "smart:inspired-by-1" or similar. Exact equality would call that a miss and
                            // send us to revert, wrongly. If this fires, read the ids= line above and use
                            // the FULL returned string in step 2 — do not reconstruct the prefix.
                            id != null && ids.any { it.endsWith(id) || it.contains(id) } -> "slot-prefixed"
                            homeDataId != null &&
                                ids.any { it.endsWith(homeDataId) || it.contains(homeDataId) } ->
                                "instance-prefixed"
                            // Zero nodes or a GraphQL error is INCONCLUSIVE, not a miss. Only a populated
                            // response with no relationship to either id is grounds to revert.
                            nodes.isEmpty() -> "inconclusive-empty"
                            else -> "none"
                        }
                        println("GladixDeezer STL-ID match=$match")
                    }
                val page = api.page("smarttracklist/$id")
                println("GladixDeezer PROBE stl=$id rootKeys=${page.keys}")
                val results = page["results"] as? JsonObject
                println(
                    "GladixDeezer PROBE stl resultsKeys=${results?.keys ?: "<no-results>"} " +
                        "error=${page["error"]?.toString()?.take(300) ?: "-"}"
                )
                println(
                    "GladixDeezer PROBE stl resultsShapes=" +
                        results?.entries?.joinToString { "${it.key}=${shapeOf(it.value)}" }
                )
                // Dump the WHOLE page, not `results ?: page`: the 2026-09-07 capture returned a
                // PRESENT-BUT-EMPTY `results`, so the elvis took that branch and printed `{}` while the
                // top-level `error` and an unexamined `payload` key went unseen. rootKeys was
                // [error, results, payload]; only `payload` is still uncharacterised.
                val raw = page.toString()
                val chunks = raw.chunked(PROBE_LOG_CAP)
                chunks.take(PROBE_MAX_CHUNKS).forEachIndexed { i, chunk ->
                    println("GladixDeezer PROBE stl raw[${i + 1}/${chunks.size}] $chunk")
                }
            }.onFailure {
                println("GladixDeezer PROBE stl FAILED ${it::class.simpleName}: ${it.message?.take(300)}")
            }
        }

        private fun shapeOf(element: JsonElement?): String = when (element) {
            is JsonArray -> "array[" + element.size + "]"
            is JsonObject -> "object{" + element.size + "}"
            null -> "absent"
            else -> "primitive"
        }

        private suspend fun probeOnce(api: DeezerApi, parser: DeezerParser, home: JsonObject) {
            if (!channelModuleProbed.compareAndSet(false, true)) return
            probeSmartTracklist(api, home)
            runCatching {
                val page = api.page(PROBE_TARGET)
                // Top level first: page.get wraps everything in `results`, and a REQUEST that was rejected
                // still returns 200 with a populated `error`, which would explain the empty rows outright.
                println("GladixDeezer PROBE target=$PROBE_TARGET rootKeys=${page.keys}")
                val results = page["results"] as? JsonObject
                println(
                    "GladixDeezer PROBE resultsKeys=${results?.keys ?: "<no-results>"} " +
                        "error=${page["error"]?.toString()?.take(300) ?: "-"}"
                )
                // Every top-level key of `results` with its shape and size. If the payload is under a
                // different key, this line names it and its length before any body is dumped.
                println(
                    "GladixDeezer PROBE resultsShapes=" +
                        results?.entries?.joinToString { "${it.key}=${shapeOf(it.value)}" }
                )
                val sections = results?.get("sections") as? JsonArray ?: JsonArray(emptyList())
                println("GladixDeezer PROBE sections=${sections.size}")
                // Per-section summary in the SAME fields logSections prints for Home, so the two can be
                // read side by side: whatever differs between a Home section and this page's sections is
                // the reason one parses and the other does not.
                sections.filterIsInstance<JsonObject>().forEachIndexed { i, sec ->
                    val itemsRaw = sec["items"]
                    val items = itemsRaw as? JsonArray ?: JsonArray(emptyList())
                    // __TYPE__ is what toEchoMediaItem dispatches on. Items present with a populated outer
                    // `type` and an EMPTY __TYPE__ map is the smarttracklist shape exactly - items there,
                    // nothing parsed - so both levels are counted.
                    val inner = parser.run {
                        items.mapNotNull { (it as? JsonObject)?.unwrap()?.str("__TYPE__") }
                            .groupingBy { it }.eachCount()
                    }
                    val outer = items.mapNotNull { (it as? JsonObject)?.get("type").prim() }
                        .groupingBy { it }.eachCount()
                    // parsed= is the whole question in one number: it runs the REAL parser over the items,
                    // so it reports what channelFeed would actually have got rather than what we think the
                    // types imply. parsed=0 with items>0 is filter (3); items=0 is filter (2);
                    // title=<null> is filter (1).
                    val parsed = parser.run { items.count { (it as? JsonObject)?.toEchoMediaItem() != null } }
                    println(
                        "GladixDeezer PROBE sec[$i] keys=${sec.keys} " +
                            "title=${sec["title"].prim() ?: "<null>"} layout=${sec["layout"].prim() ?: "?"} " +
                            "module_id=${sec["module_id"].prim() ?: "?"} target=${sec["target"].prim() ?: "?"} " +
                            "itemsShape=${shapeOf(itemsRaw)} items=${items.size} parsed=$parsed " +
                            "__TYPE__={$inner} outerType={$outer}"
                    )
                }
                // Raw body last, so the summary lines survive even if this floods. First section when there
                // is one - that is where items live; otherwise the whole `results`, which is the
                // interesting object precisely BECAUSE it has no sections.
                val raw = (sections.firstOrNull() ?: results)?.toString().orEmpty()
                val chunks = raw.chunked(PROBE_LOG_CAP)
                chunks.take(PROBE_MAX_CHUNKS).forEachIndexed { i, chunk ->
                    println("GladixDeezer PROBE raw[${i + 1}/${chunks.size}] $chunk")
                }
            }.onFailure {
                // A throw here is an answer too (a 4xx, a parse failure, a dead uuid), and it must not be
                // silent: this runs detached from any user action, so nothing else would report it.
                println("GladixDeezer PROBE FAILED ${it::class.simpleName}: ${it.message?.take(300)}")
            }
        }

        private val dispatcher = Dispatchers.Default

        private val CATEGORY_MODULE_ID = setOf(
            // Free Users
            "868606eb-4afc-4e1a-b4e4-75b30da34ac8",
            // Premium Users
            "4f6321c0-21f5-474f-8156-9f6dd6222d7c"
        )
    }
}