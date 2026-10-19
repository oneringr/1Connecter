package top.onering.easyconnecter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import top.onering.easyconnecter.model.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun _1ConnecterTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkConnectorPalette else LightConnectorPalette
    val controller = remember(dark) {
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = lightColorScheme(
                primary = ConnectorBlue,
                surface = LightConnectorPalette.background,
                background = LightConnectorPalette.background,
                surfaceContainer = LightConnectorPalette.panel,
                surfaceVariant = LightConnectorPalette.panel,
                onSurface = LightConnectorPalette.ink,
                onBackground = LightConnectorPalette.ink,
                onSurfaceVariantSummary = LightConnectorPalette.muted,
            ),
            darkColors = darkColorScheme(
                primary = ConnectorBlue,
                surface = DarkConnectorPalette.background,
                background = DarkConnectorPalette.background,
                surfaceContainer = DarkConnectorPalette.panel,
                surfaceVariant = DarkConnectorPalette.panel,
                onSurface = DarkConnectorPalette.ink,
                onBackground = DarkConnectorPalette.ink,
                onSurfaceVariantSummary = DarkConnectorPalette.muted,
            ),
        )
    }
    CompositionLocalProvider(LocalConnectorPalette provides palette) {
        MiuixTheme(controller = controller, textStyles = ConnectorTextStyles, content = content)
    }
}
