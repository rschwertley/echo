package dev.brahmkshatriya.echo.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.brahmkshatriya.echo.MainActivity
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.ui.UiUtils.dpToPx
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.WeakHashMap

class SnackBarHandler(
    val app: App,
) {

    private val messageFlow = app.messageFlow

    // Pending snackbars, drained one at a time: create() only emits when nothing is showing, and remove()
    // emits the next on dismissal. Deliberately on the Koin SINGLETON so queued messages survive activity
    // recreation - do not move it to the Activity or clear it on recreate.
    private val messages = mutableListOf<Message>()

    // Dedupe by VALUE, not by Message identity. `messages.contains(message)` compared whole Messages, and
    // Message.action holds a LAMBDA - lambdas have no structural equality, so two identical actionable
    // messages never compared equal and the dedupe silently did nothing for every one of them, including
    // every LoginRequired snackbar. Comparing the text plus the action's NAME restores it: the name is what
    // the user reads on the button, so two entries sharing both are the same notification as far as anyone
    // can tell, even though their handlers are distinct objects.
    private fun Message.dedupeKey() = message to action?.name

    // The queue was uncapped. That was survivable only because throwFlow was a zero-buffer SharedFlow whose
    // back-pressure meant a burst mostly never reached create() at all - the containment the Aug 2026 note
    // relied on. Buffering throwFlow/messageFlow (App.kt) removes exactly that, so every emission in a burst
    // now arrives here and, without the fix above, every actionable one appended. Hence a hard cap.
    // 16 is past the point of usefulness rather than a guess at a limit: snackbars are LENGTH_LONG, so a
    // full queue is already ~a minute of consecutive snackbars, and nobody reads the tail. Crashlytics keeps
    // the complete record either way - this list is a notification queue, not a log.
    // Drop OLDEST: a queue this deep is stale, and the newest message describes the current state.
    private val maxQueuedMessages = 16

    suspend fun create(message: Message) {
        if (messages.isEmpty()) messageFlow.emit(message)
        if (messages.none { it.dedupeKey() == message.dedupeKey() }) {
            messages.add(message)
            while (messages.size > maxQueuedMessages) messages.removeAt(0)
        }
    }

    suspend fun remove(message: Message, dismissed: Boolean) {
        if (dismissed) messages.remove(message)
        if (messages.isNotEmpty()) messageFlow.emit(messages.first())
    }

    companion object {
        fun MainActivity.setupSnackBar(
            uiViewModel: UiViewModel, root: View
        ): SnackBarHandler {
            val handler by inject<SnackBarHandler>()
            val padding = 8.dpToPx(this@setupSnackBar)
            @Suppress("IDENTITY_SENSITIVE_OPERATIONS_WITH_VALUE_TYPE")
            val snackBars = WeakHashMap<Int, Snackbar>()
            fun updateInsets(snackBar: Snackbar) {
                snackBar.view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val insets = uiViewModel.systemInsets.value
                    val snackbarInsets = uiViewModel.getSnackbarInsets()
                    marginStart = insets.start + snackbarInsets.start + padding
                    marginEnd = insets.end + snackbarInsets.end + padding
                    bottomMargin = snackbarInsets.bottom + padding
                }
            }
            fun createSnackBar(message: Message) {
                val snackBar = Snackbar.make(root, message.message, Snackbar.LENGTH_LONG)
                snackBar.animationMode = Snackbar.ANIMATION_MODE_SLIDE
                updateInsets(snackBar)
                message.action?.run { snackBar.setAction(name) { handler() } }
                snackBars[message.hashCode()] = snackBar
                snackBar.addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        snackBars.remove(message.hashCode())
                        lifecycleScope.launch {
                            handler.remove(message, event != DISMISS_EVENT_MANUAL)
                        }
                    }
                })
                snackBar.show()
            }

            observe(handler.messageFlow) { message ->
                createSnackBar(message)
            }
            observe(uiViewModel.combined) { _ ->
                snackBars.values.forEach { updateInsets(it) }
            }
            return handler
        }

        fun Fragment.createSnack(message: Message) {
            val handler by inject<SnackBarHandler>()
            lifecycleScope.launch { handler.create(message) }
        }

        fun Fragment.createSnack(message: String) {
            createSnack(Message(message))
        }

        fun Fragment.createSnack(message: Int) {
            createSnack(getString(message))
        }

        fun FragmentActivity.createSnack(message: Message) {
            val handler by inject<SnackBarHandler>()
            lifecycleScope.launch { handler.create(message) }
        }

        fun FragmentActivity.createSnack(message: String) {
            createSnack(Message(message))
        }
    }
}