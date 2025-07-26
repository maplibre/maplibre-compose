package org.maplibre.compose.map

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.plugins.scalebar.ScaleBarOptions
import org.maplibre.android.plugins.scalebar.ScaleBarPlugin

internal class AndroidScaleBar(ctx: Context, private val mapView: MapView, map: MapLibreMap) {
  private val plugin = ScaleBarPlugin(mapView, map)
  private val scaleBar = plugin.create(ScaleBarOptions(ctx))

  internal var enabled: Boolean
    get() = scaleBar.isEnabled
    set(value) {
      scaleBar.isEnabled = value
      scaleBar.visibility = if (value) View.VISIBLE else View.GONE
    }

  internal var layoutDir: LayoutDirection = LayoutDirection.Ltr
  internal var density: Density = Density(1f)
  internal var alignment: Alignment = Alignment.TopStart
  internal var padding: PaddingValues = PaddingValues(0.dp)

  internal fun updateLayout() =
    with(density) {
      val left = (padding.calculateLeftPadding(layoutDir).coerceAtLeast(0.dp) + 8.dp).roundToPx()
      val top = (padding.calculateTopPadding().coerceAtLeast(0.dp) + 8.dp).roundToPx()
      val right = (padding.calculateRightPadding(layoutDir).coerceAtLeast(0.dp) + 8.dp).roundToPx()
      val bottom = (padding.calculateBottomPadding().coerceAtLeast(0.dp) + 8.dp).roundToPx()

      val offset =
        alignment.align(
          size =
            IntSize(
              width = (mapView.width * scaleBar.ratio).roundToInt(),
              height =
                (scaleBar.barHeight +
                    scaleBar.textSize +
                    scaleBar.textBarMargin +
                    2 * scaleBar.borderWidth)
                  .roundToInt(),
            ),
          space =
            IntSize(width = mapView.width - left - right, height = mapView.height - top - bottom),
          layoutDirection = layoutDir,
        )

      scaleBar.marginLeft = (offset.x + left).toFloat()
      scaleBar.marginTop = (offset.y + top).toFloat()
    }
}
