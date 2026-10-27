package io.github.oruviyex18.andmtproto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.oruviyex18.andmtproto.ui.AppTheme
import io.github.oruviyex18.andmtproto.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Quick Settings tile opens the app with [ACTION_START] when it can't start the service itself. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START) ProxyService.start(this)
    }

    companion object {
        const val ACTION_START = "io.github.oruviyex18.andmtproto.action.START"
    }
}
