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
// Parsing here degrades a bad/absent json to the Firebase-free (compileOnly) path instead.
val hasGoogleServices = file("google-services.json").let { f ->
    f.exists() && runCatching { groovy.json.JsonSlurper().parse(f); true }.getOrDefault(false)
}
val gitHash = runCatching { execute("git", "rev-parse", "HEAD").take(7) }.getOrDefault("dev")
val gitCount = runCatching { execute("git", "rev-list", "--count", "HEAD").toInt() }.getOrDefault(1)
val isDirty = runCatching { execute("git", "status", "--porcelain", "-uno").isNotEmpty() }.getOrDefault(false)
val version = "3.0.$gitCount"

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
        // every Firebase call site so no-json builds never load the (compileOnly) Firebase classes.
        buildConfigField("boolean", "HAS_FIREBASE", "$hasGoogleServices")
    }

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

    // Firebase on the COMPILE classpath in every build so direct references in main source
    // resolve even without google-services.json (CI / F-Droid). Packaged (runtime) only when
    // json is present, so Firebase-free builds stay Firebase-free.
    compileOnly(platform(libs.firebase.bom))
    compileOnly(libs.bundles.firebase)
    if (hasGoogleServices) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.bundles.firebase)
    }
}

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
    doLast {
        val critical = listOf(
            "dev.brahmkshatriya.echo.common.clients.ExtensionClient",
            "dev.brahmkshatriya.echo.common.clients.TrackClient",
            "dev.brahmkshatriya.echo.common.clients.AlbumClient",
            "dev.brahmkshatriya.echo.common.clients.RadioClient",
            "dev.brahmkshatriya.echo.common.models.Track",
            "dev.brahmkshatriya.echo.common.models.EchoMediaItem",
        )
        val mappingRoot = layout.buildDirectory.dir("outputs/mapping").get().asFile
        val mappingFiles = mappingRoot.listFiles()
            ?.map { java.io.File(it, "mapping.txt") }
            ?.filter { it.exists() }
            .orEmpty()
        if (mappingFiles.isEmpty()) {
            logger.lifecycle("verifyExtensionAbi: no mapping.txt found (minify off?) — skipping ABI check.")
            return@doLast
        }
        mappingFiles.forEach { file ->
            val variant = file.parentFile.name
            val lines = file.readLines()
            critical.forEach { fqcn ->
                val selfMapped = lines.any { it.startsWith("$fqcn -> $fqcn:") }
                if (!selfMapped) throw GradleException(
                    "Extension ABI broken: $fqcn was repackaged/renamed by R8 in variant '$variant'. " +
                        "The -keep rule for dev.brahmkshatriya.echo.common.** is missing or not applied — " +
                        "third-party extensions will fail to load (NoClassDefFoundError). " +
                        "See app/proguard-rules.pro."
                )
            }
            logger.lifecycle("verifyExtensionAbi: '$variant' ABI intact (${critical.size} core classes self-mapped).")
        }
    }
}

// Run the guard whenever R8 runs (release/nightly/stable). A failure in this finalizer fails the build.
tasks.matching { it.name.matches(Regex("^minify.*WithR8$")) }.configureEach {
    finalizedBy("verifyExtensionAbi")
}
