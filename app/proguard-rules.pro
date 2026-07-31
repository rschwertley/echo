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

# 1. Our own ABI module.
-keep class dev.brahmkshatriya.echo.common.** { *; }

# 2. Kotlin stdlib — extensions declare it compileOnly and rely on the app at runtime. Covers function
#    types (kotlin.jvm.functions.Function0..N), suspend machinery (kotlin.coroutines.Continuation),
#    kotlin.Result/Unit/Pair, collections, text/regex/sequences, and @kotlin.Metadata.
-keep class kotlin.** { *; }

# 3. Coroutines + serialization — :common api-exposes these (Flow/StateFlow/SharedFlow in signatures,
#    @Serializable models). Extensions compile against them transitively and do not bundle them.
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }

# 4. okhttp (+okio) and protobuf — :common api-exposes these (OkHttpClient/Call in ContinuationCallback,
#    protobuf in settings). compileOnly/transitive in extensions -> the app must provide them by name.
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.protobuf.** { *; }

# Preserve generics + all annotation variants + nested/lambda linkage so kotlinx.serialization type
# resolution and suspend/lambda types crossing the extension classloader boundary still resolve after
# shrinking (proguard-android-optimize keeps *Annotation* but NOT Signature/InnerClasses/EnclosingMethod).
-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*
