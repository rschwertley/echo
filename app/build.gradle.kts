// Suppresses the IDE "the 'apply' plugin syntax is older and not recommended" inspection for the
// CONDITIONAL apply(plugin = …) calls below (gms/crashlytics), which cannot move to the plugins {}
// block because they must apply only when google-services.json is present (see the NOTE there).
//
// ⚠️ "GrDeprecatedAPIUsage" IS ALMOST CERTAINLY THE WRONG ID AND IS DOING NOTHING. The `Gr` prefix is
// GROOVY (the inspection ships with the Groovy plugin and targets .gradle files); this is a .gradle.KTS
// file, so it never matches and the warning keeps appearing. An unknown id is silently ignored, which is
// exactly why this looked settled and was not. Left in place only so the next person sees this note
// rather than re-deriving it.
// TO FIX PROPERLY: put the caret on the warning at the apply() calls below, Alt+Enter -> "Suppress for
// file", and let the IDE insert the correct id for your Android Studio version. Then delete this one.
@file:Suppress("GrDeprecatedAPIUsage", "AvoidDuplicateDependencies", "AvoidApplyPluginMethod")

import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)

    alias(libs.plugins.gms) apply false
    alias(libs.plugins.crashlytics) apply false
}

// True only when a PARSEABLE google-services.json is present. CI (nightly/stable) writes this file
// by base64-decoding the GOOGLE_SERVICES_B64 secret; an empty/misconfigured secret yields an empty
// or malformed file that would otherwise pass a bare exists() and hard-fail processGoogleServices.
// Parsing here degrades a bad/absent JSON to the Firebase-free (compileOnly) path instead.
val hasGoogleServices = file("google-services.json").let { f ->
    f.exists() && runCatching { groovy.json.JsonSlurper().parse(f); true }.getOrDefault(false)
}
val gitHash = runCatching { execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("dev")
val gitCount = runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val isDirty = runCatching { execute("git", "status", "--porcelain", "-uno").isNotEmpty() }.getOrDefault(false)
// "3.1." prefix + zero-padded gitCount so versionName sorts NUMERICALLY as a string in Firebase Crashlytics
// Release Monitoring (which orders the version picker lexicographically). Two things this fixes:
//  • the 3→4 digit lexicographic break ("1000" < "999"): padStart(5,'0') → "01024" > "00999" as strings;
//  • the frozen un-padded 3.0.xxx history: bumping the prefix to 3.1. sorts every new build above all old
//    "3.0.###" entries at once (they can't be re-padded retroactively).
// versionCode stays the raw gitCount (Android requires an Int; it's already monotonic). Display stays tied
// to the count: "3.1.01024" == count 1024, just padded.
val version = "3.1." + gitCount.toString().padStart(5, '0')

android {
    namespace = "dev.brahmkshatriya.echo"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rschwertley.gladix.auto"
        minSdk = 24
        targetSdk = 37
        // Note: versionCode only increments on git commits. 
        // For local development, consider committing frequently to update the version.
        versionCode = gitCount
        versionName = "v${version}_$gitHash${if (isDirty) "-dirty" else ""}($gitCount)"
        // True only when google-services.json is present. Compile-time constant used to guard
        // every Firebase call site so no-JSON builds never load the (compileOnly) Firebase classes.
        buildConfigField("boolean", "HAS_FIREBASE", "$hasGoogleServices")
    }

    // ── WHICH VARIANT GOES WHERE. Not derivable from this file, so it is written down: the next
    // "which variant is that?" question should be answered from here rather than re-inferred.
    //   release  -> GOOGLE PLAY, via `bundleRelease`.
    //              ⚠️ ON PLAY THE APP SELF-UPDATER IS OFF; EXTENSION UPDATES STAY ON. Play owns app
    //              updates. This is not a build-type rule — it is decided by INSTALL SOURCE, so a
    //              release APK sideloaded from elsewhere does self-update, deliberately.
    //              AppUpdater.isStoreInstall() is checked at the top of updateApp(), before any network
    //              work, and is fail-closed twice over: an empty installer list and a thrown exception
    //              both resolve to "store" (skip). updateApp() has exactly ONE caller
    //              (ExtensionsViewModel.update), so no entry point can route around it, and `force`
    //              lifts only the 24h throttle, never the gate. On a store install updateApp returns
    //              null and control falls into the extension-update branch unchanged — which is how a
    //              Play user keeps getting extension updates while never being offered an app update.
    //   stable   -> SIDELOADED APK, self-updated from GitHub releases (AppUpdater's "stable" branch).
    //   nightly  -> SIDELOADED APK, self-updated from nightly.link artifacts (its "nightly" branch).
    //   debug    -> local development only, never distributed, never minified.
    //
    // ⚠️ ALL THREE MINIFIED VARIANTS SHIP TO REAL USERS. Roughly 95% of users are on a sideloaded APK
    // (stable/nightly); the rest come through Play (release). So an R8 behaviour change is a
    // ship-blocker on every one of them, and both build guards below deliberately cover all three.
    //
    // ⚠️ DO NOT INFER DISTRIBUTION FROM AppUpdater. It branches on BUILD_TYPE for "stable" and
    // "nightly" only, and has no "release" branch — that is because a Play build does not SELF-update,
    // the Store updates it, not because release is undistributed. That inference was actually made and
    // was wrong (2026-09-02). AppUpdater answers "does this channel publish its own releases", which is
    // a different question from "does this variant reach users".
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // Load-bearing: without this line the release R8 run never reads proguard-rules.pro,
                // so the extension-ABI keep rule below would be silently dropped. nightly/stable inherit
                // this via initWith(getByName("release")).
                "proguard-rules.pro",
            )
        }
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            matchingFallbacks += "release"
        }
        create("stable") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    lint {
        disable.add("MissingTranslation")
        abortOnError = false
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":deezer-extension"))
    implementation(libs.kotlin.reflect)
    implementation(libs.bundles.androidx)
    implementation(libs.material)
    implementation(libs.bundles.paging)
    implementation(libs.filekache)
    implementation(libs.bundles.room)
    implementation(libs.sqlite.async)
    ksp(libs.room.compiler)
    implementation(libs.bundles.koin)
    implementation(libs.androidx.car.app)
    implementation(libs.bundles.media3)
    implementation(libs.bundles.coil)

    implementation(libs.zxing.core)
    implementation(libs.pikolo)
    implementation(libs.fadingedgelayout)
    implementation(libs.fastscroll)
    implementation(libs.nestedscrollwebview)
    implementation(libs.acsbendi.webview)

    testImplementation(libs.junit)

    // Firebase on the COMPILE classpath in every build so direct references in main source resolve
    // even without google-services.json (CI / F-Droid). Mutually exclusive by design: with JSON present
    // it is `implementation` (compile + runtime, packaged); without, it stays `compileOnly` (compile
    // only, never packaged) so Firebase-free builds stay Firebase-free. `implementation` is a superset of
    // `compileOnly` for the compile classpath, so this is identical to declaring both — but avoids putting
    // the same artifact on both configurations (the "declared multiple times" warning).
    if (hasGoogleServices) {
        @Suppress("AvoidDuplicateDependencies")
        implementation(platform(libs.firebase.bom))
        @Suppress("AvoidDuplicateDependencies")
        implementation(libs.bundles.firebase)
    } else {
        @Suppress("AvoidDuplicateDependencies")
        compileOnly(platform(libs.firebase.bom))
        compileOnly(libs.bundles.firebase)
    }
}

// NOTE: apply() is REQUIRED here, not a `plugins {}` entry — these plugins are applied CONDITIONALLY
// (only when google-services.json is present). The declarative plugins {} block cannot be conditional;
// gms/crashlytics are declared there with `apply false` and applied here. Moving them into plugins {}
// unconditionally would run processGoogleServices in every build and hard-fail the Firebase-free
// (no-JSON) path (CI / F-Droid). The "use the plugins DSL" inspection is a false positive for this case.
if (hasGoogleServices) {
    apply(plugin = libs.plugins.gms.get().pluginId)
    apply(plugin = libs.plugins.crashlytics.get().pluginId)
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()

// RECURRENCE GUARD for the extension-ABI keep rule (see app/proguard-rules.pro). Fails the release
// build if R8 repackaged/renamed any core :common ABI class — the exact symptom that breaks every
// third-party extension at load. Dumb-and-robust: string-matches a handful of critical classes to
// themselves in mapping.txt; skips gracefully when minify is off (no mapping.txt).
tasks.register("verifyExtensionAbi") {
    description = "Verifies R8 did not repackage/rename the extension ABI (common.** + kept stdlib packages), failing the build if it did."
    group = "verification"
    // Resolve everything from Project at CONFIGURATION time into plain, serializable locals (a File and a
    // List<String>). The doLast action below captures ONLY these + File I/O — no layout/logger/project
    // reference at execution time — so it is compatible with the configuration cache (the bundle build).
    val mappingRoot: File = layout.buildDirectory.dir("outputs/mapping").get().asFile
    // ── INVARIANT: EVERY `-keep class <pkg>.** { *; }` IN proguard-rules.pro NEEDS AN ANCHOR HERE. ──
    // The keep rules are a SWEEP of the whole link-but-don't-bundle ABI surface; this list is what proves
    // the sweep held. An unanchored keep rule is unverified — R8 could repackage that whole package and
    // this task would still print "ABI intact". That is not hypothetical: okio.** and
    // com.google.protobuf.** were kept but UNANCHORED from this task's creation (2026-08-01) until
    // 2026-08-24, while the failure message below already claimed to cover them.
    // Anchors are chosen to be (a) guaranteed present — the blanket keep stops R8 shrinking them, so the
    // only way one leaves mapping.txt is repackaging, which is exactly what we are testing for; (b) stable
    // across library versions — root interfaces/types, never anything marked experimental.
    // ⚠️ Do NOT add anchors one-at-a-time in response to a crash. Add one when you add a keep rule.
    val critical = listOf(
        // our ABI — dev.brahmkshatriya.echo.common.** (rule 1)
        "dev.brahmkshatriya.echo.common.clients.ExtensionClient",
        "dev.brahmkshatriya.echo.common.clients.TrackClient",
        "dev.brahmkshatriya.echo.common.clients.AlbumClient",
        "dev.brahmkshatriya.echo.common.clients.RadioClient",
        "dev.brahmkshatriya.echo.common.models.Track",
        "dev.brahmkshatriya.echo.common.models.EchoMediaItem",
        // kotlin.** (rule 2)
        "kotlin.jvm.functions.Function0",        // function types
        "kotlin.jvm.functions.Function1",
        "kotlin.coroutines.Continuation",        // suspend machinery
        // kotlinx.coroutines.** / kotlinx.serialization.** (rule 3)
        "kotlinx.coroutines.flow.Flow",
        "kotlinx.serialization.KSerializer",
        // okhttp3.** / okio.** / com.google.protobuf.** (rule 4)
        "okhttp3.OkHttpClient",
        // okio: ByteString is core to okio and referenced pervasively by okhttp — it cannot be absent
        // while okhttp is on the classpath. Anchor added 2026-08-24 (rule 4 was previously unverified).
        "okio.ByteString",
        // protobuf: MessageLite is the root interface every generated message implements, unchanged
        // across 2.x→4.x. Deliberately NOT an experimental type (v36.0 removed the experimental
        // FieldOrder enum). Anchor added 2026-08-24 (rule 4 was previously unverified).
        "com.google.protobuf.MessageLite",
    )
    doLast {
        val mappingFiles: List<File> = (mappingRoot.listFiles()?.toList().orEmpty())
            .map { dir -> File(dir, "mapping.txt") }
            .filter { it.exists() }
        if (mappingFiles.isEmpty()) {
            println("verifyExtensionAbi: no mapping.txt found (minify off?) — skipping ABI check.")
            return@doLast
        }
        mappingFiles.forEach { file ->
            val variant = file.parentFile?.name ?: "unknown"
            val lines = file.readLines()
            critical.forEach { fqcn ->
                val selfMapped = lines.any { line -> line.startsWith("$fqcn -> $fqcn:") }
                if (!selfMapped) throw GradleException(
                    "Extension ABI broken: $fqcn was repackaged/renamed by R8 in variant '$variant'. " +
                        "An extension-ABI -keep rule is missing or not applied — extensions will fail to " +
                        "load (NoClassDefFoundError). The kept ABI is common.** + kotlin.** + " +
                        "kotlinx.coroutines.** + kotlinx.serialization.** + okhttp3.** + okio.** + " +
                        "com.google.protobuf.**. See app/proguard-rules.pro."
                )
            }
            println("verifyExtensionAbi: '$variant' ABI intact (${critical.size} core classes self-mapped).")
        }
    }
}

// Run the guard whenever R8 runs — release, nightly AND stable. All three are distributed (see the note
// on buildTypes above: Play gets release, sideloads get stable/nightly), so all three can break
// extensions and all three must be verified. A failure in this finalizer fails the build.
tasks.matching { it.name.matches(Regex("^minify.*WithR8$")) }.configureEach {
    finalizedBy("verifyExtensionAbi")
}

// The OTHER half of the extension-ABI guarantee, gated on the same trigger so a shippable build cannot be
// produced without both. They cover disjoint failure modes and neither subsumes the other:
//   verifyExtensionAbi (finalizedBy, above) - POST-compile: did R8 rename/repackage the kept classes?
//   :common:checkKotlinAbi (dependsOn, here) - PRE-compile: did the public ABI of :common itself change?
// dependsOn rather than finalizedBy because this one needs no build output, so failing before R8 runs is
// strictly cheaper. See the abiValidation block in common/build.gradle.kts for the bootstrap step.
tasks.matching { it.name.matches(Regex("^minify.*WithR8$")) }.configureEach {
    dependsOn(":common:checkKotlinAbi")
}

// ── A SHIPPABLE BUILD MUST NOT COMPILE ON TOP OF PREVIOUS KOTLIN OUTPUT ──
// Build 1058 shipped an APK in which StreamableLoader still called App.getFileCache(), a getter that had
// been deleted two commits earlier. Nothing was wrong with the source. Cached.loadMedia/getMedia are
// PUBLIC INLINE, so their bodies are copied into every CALLER's class file; the commit that changed them
// recompiled Cached.kt but not its callers, and those orphaned copies kept a call to a member that no
// longer existed. Every track resolve then died with NoSuchMethodError at runtime.
//
// Neither half of the toolchain can see this. The compiler never re-checks an already-inlined body (the
// caller is simply not recompiled, so nothing looks at it), and R8 packaged the dangling member reference
// silently - there is no -dontwarn suppressing it. Compiling a shippable variant from nothing is the only
// reliable defence, and it is the GENERAL fix: it covers every future edit to any public inline function,
// not just this one.
//
// This gate deliberately does NOT clean for you. A clean task wired into the same build has no ordering
// guarantee against the compile it is meant to precede, so it would be a fix that silently stops working.
// It REFUSES instead - same shape as verifyExtensionAbi above: a cheap check with an actionable message.
//
// Scope is the minified variants only. Debug stays incremental and fast: it is never shipped, and its
// output lives in a different directory, so it cannot leak into a release compilation.
//
// ⚠️ Fail-open if the task-name regex ever stops matching (a product flavor would make the names
// `compile<Flavor><BuildType>Kotlin`). If flavors are ever added, widen the regex or this silently
// protects nothing. The variant list below must likewise track buildTypes {} above.
tasks.register("verifyCleanKotlinOutput") {
    description = "Fails a release/nightly/stable build that would compile on top of existing Kotlin output."
    // All three are shipped variants (see the note on buildTypes) — hence all three guarded, and debug
    // deliberately not: it is never distributed, and gating it would force a clean build on every
    // day-to-day compile.
    group = "verification"
    // Resolved at CONFIGURATION time into plain serializable locals so the action below captures no
    // Project reference - same configuration-cache constraint as verifyExtensionAbi.
    val kotlinClassesRoot: File = layout.buildDirectory.dir("tmp/kotlin-classes").get().asFile
    val guarded: List<String> = listOf("release", "nightly", "stable")
    // In `gradlew clean bundleRelease` the two are independent roots; without this they could be ordered
    // either way and a genuinely clean build could still trip the check.
    mustRunAfter("clean")
    doLast {
        val dirty = guarded
            .map { variant -> File(kotlinClassesRoot, variant) }
            .filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.extension == "class" } }
        if (dirty.isNotEmpty()) throw GradleException(
            "Refusing to build a shippable variant on top of existing Kotlin output " +
                "(${dirty.joinToString(", ") { it.name }}). Incremental compilation can leave a caller " +
                "holding a STALE copy of a public inline function's body: build 1058 shipped with " +
                "StreamableLoader still calling the deleted App.getFileCache(), and every track resolve " +
                "failed with NoSuchMethodError. Run './gradlew clean' first, then rebuild."
        )
    }
}

tasks.matching { it.name.matches(Regex("^compile(Release|Nightly|Stable)Kotlin$")) }.configureEach {
    dependsOn("verifyCleanKotlinOutput")
}
