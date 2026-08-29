plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
    id("com.vanniktech.maven.publish") version "0.37.0"
    id("org.jetbrains.dokka") version "2.2.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)

    // ── PUBLIC-ABI LOCK FOR THE EXTENSION SURFACE ──
    // :common is what every extension compiles against - the :deezer-extension module by project
    // dependency, third parties via the Maven Central publication below. Anything public here is therefore
    // ABI: removing a member, narrowing its visibility or changing its signature breaks every ALREADY
    // INSTALLED extension at runtime, and nothing else in this build can see that. The Kotlin compiler
    // cannot (extensions are not in the repo, so there is no caller to fail), and app's verifyExtensionAbi
    // cannot (it proves R8 did not RENAME the kept packages - it never looks at members).
    //
    // `checkKotlinAbi` dumps the public ABI and diffs it against the committed reference dump, failing
    // the build on any divergence. A deliberate API change then shows up as a visible diff in that file
    // rather than as a one-word edit nobody reviews.
    //
    // Bootstrap (once): ./gradlew :common:updateKotlinAbi   then COMMIT the dump it writes.
    // After any intended API change: re-run updateKotlinAbi and commit the diff alongside the change.
    //
    // ⚠️ TASK NAMES ARE VERSION-SPECIFIC. On Kotlin 2.4.10 they are `checkKotlinAbi` / `updateKotlinAbi`,
    // verified by extracting org/jetbrains/kotlin/gradle/plugin/abi + tasks/abi from the KGP jar - NOT the
    // `checkLegacyAbi` / `updateLegacyAbi` the current online docs describe, which belong to an earlier
    // DSL and do not exist here. app/build.gradle.kts depends on the check task BY NAME, so if a Kotlin
    // bump renames it that dependsOn fails loudly (unknown task) rather than silently skipping - but
    // re-verify from the jar rather than the docs when bumping.
    //
    // Built-in Kotlin Gradle plugin feature, NOT the standalone kotlinx binary-compatibility-validator
    // plugin - no extra coordinate, so nothing to keep in step with the Kotlin version. The DSL is marked
    // experimental, hence the opt-in; if a Kotlin bump breaks it, the fallback is that same standalone
    // plugin, which produces an equivalent dump.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        // Intentionally empty: CALLING this block is what enables validation. The `enabled` property
        // still exists on AbiValidationMultiplatformExtension but is deprecated to an ERROR
        // ("Property was removed, to enable ABI validation call function abiValidation()"), so setting
        // it fails the build. Do not "fix" this by adding enabled.set(true) back.
    }

    android {
        namespace = "echo.common"
        compileSdk = 37
        minSdk = 24
    }
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.bundles.kotlinx)
                api(libs.okhttp)
                api(libs.protobuf.java)
            }
        }
    }
}

// build.gradle.kts

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()

    coordinates("dev.brahmkshatriya.echo", "common", "1.0.0")

    pom {
        name = "Echo common library"
        description = "A common library for echo extensions."
        inceptionYear = "2025"
        url = "https://github.com/brahmkshatriya/echo"
        licenses {
            license {
                name = "Unabandon Public License"
                url = "https://github.com/brahmkshatriya/echo/blob/main/LICENSE.md"
                distribution = "https://github.com/brahmkshatriya/echo/blob/main/LICENSE.md"
            }
        }
        developers {
            developer {
                id = "brahmkshatriya"
                name = "Shivam"
                url = "https://github.com/brahmkshatriya/"
            }
        }
        scm {
            url = "https://github.com/brahmkshatriya/echo/"
            connection = "scm:git:git://github.com/brahmkshatriya/echo.git"
            developerConnection = "scm:git:ssh://git@github.com/brahmkshatriya/echo.git"
        }
    }
}

dokka {
    moduleName.set("common")
    moduleVersion.set("1.0")
    dokkaSourceSets.commonMain {
        includes.from("README.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/brahmkshatriya/echo/tree/main/common/src/main/java")
            remoteLineSuffix.set("#L")
        }
    }
    pluginsConfiguration.html {
        customStyleSheets.from("styles.css")
        footerMessage.set("made by <a style=\"color: inherit; text-decoration: underline;\" href=\"https://github.com/brahmkshatriya\">@brahmkshatriya</a>")
    }
}