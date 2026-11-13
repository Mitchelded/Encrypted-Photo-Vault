package io.github.epv.vault

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** In-process observable status backed by private preferences for reopening the UI. */
object ScanBackgroundState {
    var runningMode by mutableStateOf("")
        private set
    var message by mutableStateOf("")
        private set

    private const val PREFS = "epv_background_scan"

    fun refresh(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runningMode = preferences.getString("mode", "") ?: ""
        message = preferences.getString("message", "") ?: ""
    }

    fun publish(context: Context, mode: String, text: String) {
        runningMode = mode
        message = text
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode)
            .putString("message", text.take(600))
            .apply()
    }
}
