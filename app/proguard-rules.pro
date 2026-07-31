# CRITICAL — DO NOT REMOVE. :common is the ABI for dynamically-loaded
# extension APKs. R8 (since AGP 9.1) repackages classes into the default
# package by default, which moves/renames these classes and makes every
# third-party extension fail to load (NoClassDefFoundError). This keep
# rule preserves the extension ABI (names + package). After ANY AGP/R8/
# proguard change, verify these classes still map to themselves in
# build/outputs/mapping/release/mapping.txt (the verifyExtensionAbi task
# in app/build.gradle.kts enforces this and fails the build otherwise).
-keep class dev.brahmkshatriya.echo.common.** { *; }
