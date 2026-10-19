package top.onering.easyconnecter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val ConnectorBlue = Color(0xFF3482FF)

@Immutable
data class ConnectorPalette(
    val dark: Boolean = false,
    val background: Color = Color(0xFFF7F7F7),
    val panel: Color = Color.White,
    val ink: Color = Color(0xFF151515),
    val muted: Color = Color(0xFF666666),
    val accentText: Color = Color(0xFF2168D5),
    val accentSoft: Color = Color(0xFFEAF2FF),
    val divider: Color = Color(0xFFE0E0E0),
    val success: Color = Color(0xFF20825B),
    val warning: Color = Color(0xFF98621C),
    val danger: Color = Color(0xFFC83F46),
)

val LightConnectorPalette = ConnectorPalette()
val DarkConnectorPalette = ConnectorPalette(
    dark = true,
    background = Color(0xFF111111),
    panel = Color(0xFF242424),
    ink = Color(0xFFF2F2F2),
    muted = Color(0xFFAAAAAA),
    accentText = Color(0xFF8DB8FF),
    accentSoft = Color(0xFF233650),
    divider = Color(0xFF393939),
    success = Color(0xFF6BC59C),
    warning = Color(0xFFE4B468),
    danger = Color(0xFFFF9198),
)

val LocalConnectorPalette = staticCompositionLocalOf { LightConnectorPalette }
