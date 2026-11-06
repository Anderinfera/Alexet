package dev.ander.alexet.ui.components

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.ander.alexet.R

/** Same window as leaving the app from the folder list. */
private const val DOUBLE_BACK_WINDOW_MS = 2_000L

/**
 * Back has to be pressed twice within [DOUBLE_BACK_WINDOW_MS] to run the action, like leaving the
 * app: the first press only shows a hint. Returns the function to call from a BackHandler.
 */
@Composable
fun rememberDoubleBack(): (onConfirmed: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        var lastPressAt = 0L
        { onConfirmed ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastPressAt < DOUBLE_BACK_WINDOW_MS) {
                lastPressAt = 0L
                onConfirmed()
            } else {
                lastPressAt = now
                Toast.makeText(context, R.string.press_back_again_to_close_editor, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
