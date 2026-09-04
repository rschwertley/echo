package dev.brahmkshatriya.echo.extensions.exceptions

/**
 * An extension's loadItem returned a DIFFERENT item than the one requested. Thrown by Cached.loadMedia's
 * canonical-id guard.
 *
 * ⚠️ EXISTS TO BE CLASSIFIABLE. It replaces a bare `error(...)`, i.e. an anonymous IllegalStateException.
 * The consecutive-skip reports group on the exception CLASS, so an app-side invariant that throws a base
 * type is indistinguishable from any other app-side throw and lands in the residual bucket with
 * everything else. Naming it is what lets PlayerEventListener route it to
 * ConsecutiveSkipInternalException — the bucket that is actually watched.
 *
 * Keep the ids in [message]: they are what makes a report actionable, and Cached.idForMessage already
 * bounds them.
 */
class WrongItemException(override val message: String) : Exception()
