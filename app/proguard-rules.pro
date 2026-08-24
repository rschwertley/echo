# CRITICAL — DO NOT REMOVE. This block is the COMPLETE extension ABI: everything a dynamically-loaded
# extension APK links against but does NOT bundle, so the app (parent classloader) must provide it by
# its ORIGINAL name at runtime. R8 (since AGP 9.1) repackages classes into the default package by
# default, moving/renaming them and making every extension fail to load (NoClassDefFoundError — first
# common.**, then kotlin.jvm.functions.Function0, etc.). The set is derived from the canonical extension
# contract (echo-extension-template / deezer-extension/app: compileOnly(dev.brahmkshatriya.echo:common)
# + compileOnly(kotlin-stdlib)), where :common api-exposes kotlinx + okhttp + protobuf — NOT from chasing
# individual crashes. Parent-first DexClassLoader delegation + the extension's own -dontobfuscate mean an
# in-contract extension cannot reference a host package outside this set. After ANY AGP/R8/proguard change,
# the verifyExtensionAbi task (app/build.gradle.kts) fails the build if any anchor stops self-mapping in
# build/outputs/mapping/<variant>/mapping.txt.
#
# ⚠️ ADDING A KEEP RULE HERE? ADD AN ANCHOR TO `critical` IN app/build.gradle.kts TOO. A keep rule with no
# anchor is UNVERIFIED: R8 can repackage that whole package and verifyExtensionAbi still reports "ABI
# intact". Each rule below names its anchor(s) so a rule without one is visible at a glance. (okio and
# protobuf were unanchored 2026-08-01 → 2026-08-24 — the gap this annotation exists to prevent.)

# 1. Our own ABI module.
-keep class dev.brahmkshatriya.echo.common.** { *; }
# anchors: ExtensionClient, TrackClient, AlbumClient, RadioClient, Track, EchoMediaItem

# 2. Kotlin stdlib — extensions declare it compileOnly and rely on the app at runtime. Covers function
#    types (kotlin.jvm.functions.Function0..N), suspend machinery (kotlin.coroutines.Continuation),
#    kotlin.Result/Unit/Pair, collections, text/regex/sequences, and @kotlin.Metadata.
-keep class kotlin.** { *; }
# anchors: kotlin.jvm.functions.Function0, Function1, kotlin.coroutines.Continuation

# 3. Coroutines + serialization — :common api-exposes these (Flow/StateFlow/SharedFlow in signatures,
#    @Serializable models). Extensions compile against them transitively and do not bundle them.
-keep class kotlinx.coroutines.** { *; }
# anchor: kotlinx.coroutines.flow.Flow
-keep class kotlinx.serialization.** { *; }
# anchor: kotlinx.serialization.KSerializer

# 4. okhttp (+okio) and protobuf — :common api-exposes these (OkHttpClient/Call in ContinuationCallback,
#    protobuf in settings). compileOnly/transitive in extensions -> the app must provide them by name.
-keep class okhttp3.** { *; }
# anchor: okhttp3.OkHttpClient
-keep class okio.** { *; }
# anchor: okio.ByteString
-keep class com.google.protobuf.** { *; }
# anchor: com.google.protobuf.MessageLite

# Preserve generics + all annotation variants + nested/lambda linkage so kotlinx.serialization type
# resolution and suspend/lambda types crossing the extension classloader boundary still resolve after
# shrinking (proguard-android-optimize keeps *Annotation* but NOT Signature/InnerClasses/EnclosingMethod).
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*
