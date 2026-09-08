package org.maplibre.compose.demoapp.demos

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.harmonizeWithPrimary
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.editable_marker_pin

internal enum class MarkerColor(val label: String, val seed: Color? = null) {
  Theme("Theme"),
  Coral("Coral", Color(0xFFE96556)),
  Amber("Amber", Color(0xFFE8AD32)),
  Mint("Mint", Color(0xFF209D82)),
  Violet("Violet", Color(0xFF8855C9)),
}

@Composable
internal fun markerColorScheme(choice: MarkerColor): ColorScheme {
  val theme = MaterialTheme.colorScheme
  val seed = choice.seed ?: return theme
  return remember(seed, theme.primary, theme.surface) {
    dynamicColorScheme(
      seedColor = theme.harmonizeWithPrimary(seed),
      isDark = theme.surface.luminance() < 0.5f,
      specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
  }
}

@Composable
internal fun rememberMarkerPainter(colors: ColorScheme): Painter {
  val silhouette = painterResource(Res.drawable.editable_marker_pin)
  val outline = MaterialTheme.colorScheme.surface
  return remember(silhouette, colors.primary, colors.onPrimary, outline) {
    object : Painter() {
      override val intrinsicSize = Size(40f, 48f)

      override fun DrawScope.onDraw() {
        with(silhouette) { draw(size, colorFilter = ColorFilter.tint(outline)) }
        inset(horizontal = size.width * 0.055f, vertical = size.height * 0.045f) {
          with(silhouette) { draw(size, colorFilter = ColorFilter.tint(colors.primary)) }
        }
        val center = Offset(size.width / 2f, size.height * 0.4f)
        drawCircle(colors.onPrimary, size.width * 0.21f, center)
        drawCircle(colors.primary, size.width * 0.085f, center)
      }
    }
  }
}
