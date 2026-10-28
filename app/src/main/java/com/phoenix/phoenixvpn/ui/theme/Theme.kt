package com.phoenix.phoenixvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val Scheme = darkColorScheme(
    background = Bg,
    surface = Surface,
    primary = Teal,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun PhoenixVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        content = content
    )
}
