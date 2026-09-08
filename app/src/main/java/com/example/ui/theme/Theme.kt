package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
  primary = ClaudeTerracottaLight,
  onPrimary = Color.White,
  primaryContainer = ClaudeTerracottaDark,
  onPrimaryContainer = Color(0xFFFFDBCF),
  secondary = ClaudeTerracottaLight,
  onSecondary = Color.White,
  secondaryContainer = ClaudeSurfaceElevatedDark,
  onSecondaryContainer = ClaudeTextPrimaryDark,
  tertiary = Color(0xFFD48B38),
  background = ClaudeBackgroundDark,
  onBackground = ClaudeTextPrimaryDark,
  surface = ClaudeSurfaceDark,
  onSurface = ClaudeTextPrimaryDark,
  surfaceVariant = ClaudeSurfaceElevatedDark,
  onSurfaceVariant = ClaudeTextSecondaryDark,
  outline = ClaudeBorderDark,
  outlineVariant = Color(0xFF2E2C29)
)

private val LightColorScheme = lightColorScheme(
  primary = ClaudeTerracotta,
  onPrimary = Color.White,
  primaryContainer = ClaudeTerracottaContainer,
  onPrimaryContainer = ClaudeOnTerracottaContainer,
  secondary = ClaudeTerracottaDark,
  onSecondary = Color.White,
  secondaryContainer = ClaudeSurfaceElevatedLight,
  onSecondaryContainer = ClaudeTextPrimaryLight,
  tertiary = Color(0xFF8B5E3C),
  background = ClaudeBackgroundLight,
  onBackground = ClaudeTextPrimaryLight,
  surface = ClaudeSurfaceLight,
  onSurface = ClaudeTextPrimaryLight,
  surfaceVariant = ClaudeSurfaceElevatedLight,
  onSurfaceVariant = ClaudeTextSecondaryLight,
  outline = ClaudeBorderLight,
  outlineVariant = Color(0xFFDCD4C8)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Preserve our distinctive Claude terracotta aesthetic
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

