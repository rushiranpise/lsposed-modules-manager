package com.rushi.modulesmanager.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E9),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4F5B92),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE1FF),
    onTertiaryContainer = Color(0xFF071A60),
    background = Color(0xFFF5FAFA),
    onBackground = Color(0xFF161D1E),
    surface = Color(0xFFF5FAFA),
    onSurface = Color(0xFF161D1E),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4849),
    surfaceContainer = Color(0xFFEAF0F0),
    surfaceContainerHigh = Color(0xFFE4EBEB),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBEC8C9),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF1F6),
    secondary = Color(0xFFB1CCCE),
    onSecondary = Color(0xFF1B3436),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8E9),
    tertiary = Color(0xFFB8C3FF),
    onTertiary = Color(0xFF1F2C61),
    tertiaryContainer = Color(0xFF374379),
    onTertiaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDDE4E4),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDDE4E4),
    surfaceVariant = Color(0xFF3F4849),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainer = Color(0xFF1A2021),
    surfaceContainerHigh = Color(0xFF242B2C),
    outline = Color(0xFF899394),
    outlineVariant = Color(0xFF3F4849),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val LocalAmoled = staticCompositionLocalOf { false }

/** Cards go fully black in AMOLED mode and gain a hairline outline. */
@Composable
fun amoledCardContainerColor(): Color =
    if (LocalAmoled.current) Color.Black else MaterialTheme.colorScheme.surfaceContainer

@Composable
fun amoledCardBorder(): BorderStroke? =
    if (LocalAmoled.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

// Keep the active accents, including Material You ones, but turn the surfaces off.
private fun ColorScheme.withAmoledSurfaces(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070707),
    surfaceContainer = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF151515),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2C2C2C),
)

@Composable
fun ModulesManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val colorScheme = if (darkTheme && amoled) baseScheme.withAmoledSurfaces() else baseScheme
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalAmoled provides (darkTheme && amoled)) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
