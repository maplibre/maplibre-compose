package org.maplibre.compose.demoapp

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

/** The MapLibre brand blue used as the MaterialKolor seed. */
internal val MapLibreBrand = Color(0xFF285DAA)

internal fun PaletteMode.toPaletteStyle(): PaletteStyle? =
  when (this) {
    PaletteMode.System -> null
    PaletteMode.Tonal -> PaletteStyle.TonalSpot
    PaletteMode.Neutral -> PaletteStyle.Neutral
    PaletteMode.Vibrant -> PaletteStyle.Vibrant
    PaletteMode.Expressive -> PaletteStyle.Expressive
  }

/** A 2025-spec scheme seeded with [MapLibreBrand] for the given [PaletteStyle]. */
internal fun brandColorScheme(dark: Boolean, style: PaletteStyle): ColorScheme =
  dynamicColorScheme(
    seedColor = MapLibreBrand,
    isDark = dark,
    style = style,
    specVersion = ColorSpec.SpecVersion.SPEC_2025,
  )

/** The Material You color scheme, or null when this platform has none. */
@Composable internal expect fun rememberSystemColorScheme(dark: Boolean): ColorScheme?

/**
 * The color scheme for [paletteMode]: Material You when that choice is selected and available,
 * otherwise a MapLibre brand scheme.
 */
@Composable
fun rememberDemoColorScheme(dark: Boolean, paletteMode: PaletteMode): ColorScheme {
  val systemScheme = rememberSystemColorScheme(dark)
  val style = paletteMode.toPaletteStyle() ?: PaletteStyle.TonalSpot
  val brandScheme =
    rememberDynamicColorScheme(
      seedColor = MapLibreBrand,
      isDark = dark,
      style = style,
      specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
  return if (paletteMode == PaletteMode.System) systemScheme ?: brandScheme else brandScheme
}
