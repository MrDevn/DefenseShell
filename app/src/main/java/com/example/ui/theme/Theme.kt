package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
  primary = ClaudeTerracottaLight,
  onPrimary = Color.White,
  primaryContainer = ClaudeTerracottaDark,
  onPrimaryContainer = Color(0xFFF0F1FF),
  secondary = ClaudeTerracottaLight,
  onSecondary = Color.White,
  secondaryContainer = ClaudeSurfaceElevatedDark,
  onSecondaryContainer = ClaudeTextPrimaryDark,
  tertiary = ClaudeWarning,
  background = ClaudeBackgroundDark,
  onBackground = ClaudeTextPrimaryDark,
  surface = ClaudeSurfaceDark,
  onSurface = ClaudeTextPrimaryDark,
  surfaceVariant = ClaudeSurfaceElevatedDark,
  onSurfaceVariant = ClaudeTextSecondaryDark,
  outline = ClaudeBorderDark,
  outlineVariant = Color(0xFF27282D)
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
  tertiary = ClaudeTerracottaDark,
  background = ClaudeBackgroundLight,
  onBackground = ClaudeTextPrimaryLight,
  surface = ClaudeSurfaceLight,
  onSurface = ClaudeTextPrimaryLight,
  surfaceVariant = ClaudeSurfaceElevatedLight,
  onSurfaceVariant = ClaudeTextSecondaryLight,
  outline = ClaudeBorderLight,
  outlineVariant = Color(0xFFE9E9E5)
)

private val AppShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Preserve the distinctive CodeStudio azure aesthetic
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    shapes = AppShapes,
    content = content
  )
}
