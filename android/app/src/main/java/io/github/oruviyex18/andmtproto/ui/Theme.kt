package io.github.oruviyex18.andmtproto.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material 3 theme with dynamic colours on Android 12+. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Green for "the proxy runs". Material 3 has no success role and dynamic colour
 * may produce no green at all, so this one is fixed, in light and dark variants.
 */
object StateColors {
    val good: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
    val onGood: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF00390A) else Color.White
    val goodContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1E4620) else Color(0xFFC8E6C9)
    val onGoodContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC8E6C9) else Color(0xFF0D3B12)
}
