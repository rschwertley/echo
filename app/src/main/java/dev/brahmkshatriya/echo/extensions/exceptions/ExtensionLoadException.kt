package dev.brahmkshatriya.echo.extensions.exceptions

// Thrown when the DEFERRED class-load/instantiate step (ExtensionParser.loadFrom, run lazily at first
// use) fails — e.g. a missing/repackaged class (NoClassDefFoundError from an app-side ABI break),
// a bad dex, or a missing native lib. Carries the extension's identity so the surfaced title can name
// it and the real cause. Distinct from ExtensionLoaderException, which wraps manifest PARSING failures.
class ExtensionLoadException(
    val name: String,
    val id: String,
    val className: String,
    override val cause: Throwable
) : Exception()
