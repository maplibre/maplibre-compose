package org.maplibre.compose.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.maplibre.compose.util.drawPathsWithHalo
import org.maplibre.compose.util.drawTextWithHalo
import org.maplibre.compose.util.rememberNumberFormatter
import org.maplibre.spatialk.units.International.Meters
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.meters

/**
 * A scale bar composable that shows the current scale of the map in feet, meters or feet and meters
 * when zoomed in to the map, changing to miles and kilometers, respectively, when zooming out.
 *
 * [metersPerDp] is called while drawing. Read map state inside it rather than capturing a value.
 *
 * The Material 3 module provides a themed version.
 *
 * @param metersPerDp how many meters are displayed in one device independent pixel (dp), i.e. the
 *   scale. See
 *   [Viewport.metersPerDpAtTarget][org.maplibre.compose.camera.Viewport.metersPerDpAtTarget]
 * @param modifier the [Modifier] to be applied to this layout node
 * @param measures which measures to show on the scale bar. The default follows the system settings,
 *   or otherwise the user's locale.
 * @param color scale bar and text color.
 * @param haloColor halo for better visibility when displayed on top of the map
 * @param haloWidth scale bar and text halo width
 * @param barWidth scale bar width
 * @param textStyle Text style. The font size determines the scale bar height.
 * @param alignment horizontal alignment of the scale bar and text
 */
@Composable
public fun ScaleBar(
  metersPerDp: () -> Double,
  modifier: Modifier = Modifier,
  measures: ScaleBarMeasures = ScaleBarDefaults.measures(),
  color: Color = ScaleBarDefaults.ContentColor,
  haloColor: Color = ScaleBarDefaults.HaloColor,
  haloWidth: Dp = ScaleBarDefaults.HaloWidth,
  barWidth: Dp = ScaleBarDefaults.BarWidth,
  textStyle: TextStyle = ScaleBarDefaults.ContentTextStyle,
  alignment: Alignment.Horizontal = Alignment.Start,
) {
  val currentMetersPerDp by rememberUpdatedState(metersPerDp)

  // A zero scale means the map is not initialized yet: emit no layout node.
  val initialized by remember { derivedStateOf { currentMetersPerDp() > 0.0 } }
  if (!initialized) return

  val textMeasurer = rememberTextMeasurer()

  // longest possible text
  val formatter = rememberNumberFormatter(Locale.current)
  val maxTextSizePx =
    remember(textMeasurer, textStyle, formatter) {
      textMeasurer.measure("${formatter.format(50000)}\u202Fkm", textStyle).size
    }
  val maxTextSize = with(LocalDensity.current) { maxTextSizePx.toSize().toDpSize() }

  val textHorizontalPadding = 4.dp
  val textVerticalPadding = 0.dp

  // Adjacent stops can differ by a factor of 2.5, such as 2 km and 5 km. Reserve enough
  // width to keep the text inside the bar at the shorter stop.
  val totalMaxWidth = maxTextSize.width * 2.5f + (textHorizontalPadding + barWidth) * 2f

  val fullStrokeWidth = haloWidth * 2 + barWidth

  val textCount = if (measures.secondary != null) 2 else 1
  val totalHeight = (maxTextSize.height + textVerticalPadding) * textCount + fullStrokeWidth

  BoxWithConstraints(modifier.size(totalMaxWidth, totalHeight)) {
    // scale bar start/end should not overlap horizontally with canvas bounds
    val maxBarLength = maxWidth - fullStrokeWidth

    val stops by
      remember(measures, maxBarLength) {
        derivedStateOf {
          val scale = currentMetersPerDp().takeIf { it > 0.0 } ?: return@derivedStateOf null
          val max = scale.meters * maxBarLength.value.toDouble()
          Pair(
            findStop(max, measures.primary.stops),
            measures.secondary?.let { findStop(max, it.stops) },
          )
        }
      }
    val (primaryStop, secondaryStop) = stops ?: return@BoxWithConstraints
    val primaryText = measures.primary.getText(primaryStop)
    val secondaryText = secondaryStop?.let { checkNotNull(measures.secondary).getText(it) }

    Canvas(Modifier.fillMaxSize()) {
      val scale = currentMetersPerDp()
      if (scale <= 0.0) return@Canvas
      val fullStrokeWidthPx = fullStrokeWidth.toPx()
      val textHeightPx = maxTextSizePx.height
      val textVerticalPaddingPx = textVerticalPadding.toPx()

      // bar ends should go to the vertical center of the text
      val barEndsHeightPx = textHeightPx / 2f + textVerticalPaddingPx + fullStrokeWidthPx / 2f

      val paths = ArrayList<List<Offset>>(2)
      val alignmentSpacePx = ceil(size.width - fullStrokeWidthPx).toInt()

      run {
        val barLengthPx = (primaryStop.toDouble(Meters) / scale).dp.toPx().roundToInt()
        val offsetX =
          alignment.align(
            size = barLengthPx,
            space = alignmentSpacePx,
            layoutDirection = layoutDirection,
          )
        paths.add(
          listOf(
            Offset(offsetX + fullStrokeWidthPx / 2f, textHeightPx / 2f),
            Offset(0f, barEndsHeightPx),
            Offset(barLengthPx.toFloat(), 0f),
            Offset(0f, -barEndsHeightPx),
          )
        )
      }

      if (secondaryStop != null) {
        val y = textHeightPx + textVerticalPaddingPx
        val barLengthPx = (secondaryStop.toDouble(Meters) / scale).dp.toPx().roundToInt()
        val offsetX =
          alignment.align(
            size = barLengthPx,
            space = alignmentSpacePx,
            layoutDirection = layoutDirection,
          )
        paths.add(
          listOf(
            Offset(offsetX + fullStrokeWidthPx / 2f, y + fullStrokeWidthPx / 2f + barEndsHeightPx),
            Offset(0f, -barEndsHeightPx),
            Offset(barLengthPx.toFloat(), 0f),
            Offset(0f, barEndsHeightPx),
          )
        )
      }

      drawPathsWithHalo(
        color = color,
        haloColor = haloColor,
        paths = paths,
        strokeWidth = barWidth.toPx(),
        haloWidth = haloWidth.toPx(),
        cap = StrokeCap.Round,
      )
    }

    ScaleBarLabels(
      primary = primaryText,
      secondary = secondaryText,
      textStyle = textStyle,
      color = color,
      haloColor = haloColor,
      haloWidth = haloWidth,
      fullStrokeWidth = fullStrokeWidth,
      textHeightPx = maxTextSizePx.height,
      textHorizontalPadding = textHorizontalPadding,
      textVerticalPadding = textVerticalPadding,
      alignment = alignment,
    )
  }
}

@Composable
private fun ScaleBarLabels(
  primary: String,
  secondary: String?,
  textStyle: TextStyle,
  color: Color,
  haloColor: Color,
  haloWidth: Dp,
  fullStrokeWidth: Dp,
  textHeightPx: Int,
  textHorizontalPadding: Dp,
  textVerticalPadding: Dp,
  alignment: Alignment.Horizontal,
) {
  val textMeasurer = rememberTextMeasurer()
  val primaryLayout =
    remember(textMeasurer, primary, textStyle) { textMeasurer.measure(primary, textStyle) }
  val secondaryLayout =
    remember(textMeasurer, secondary, textStyle) {
      secondary?.let { textMeasurer.measure(it, textStyle) }
    }
  Canvas(Modifier.fillMaxSize().graphicsLayer()) {
    val fullStrokeWidthPx = fullStrokeWidth.toPx()
    val textVerticalPaddingPx = textVerticalPadding.toPx()
    val x = textHorizontalPadding.toPx() + fullStrokeWidthPx
    val texts = ArrayList<Pair<Offset, TextLayoutResult>>(2)
    texts.add(Pair(Offset(x, 0f), primaryLayout))
    if (secondaryLayout != null) {
      val y = textHeightPx + textVerticalPaddingPx
      texts.add(Pair(Offset(x, y + textVerticalPaddingPx + fullStrokeWidthPx), secondaryLayout))
    }
    for ((offset, textLayoutResult) in texts) {
      val offsetX =
        alignment.align(
          size = textLayoutResult.size.width,
          space = ceil(size.width - 2 * offset.x).toInt(),
          layoutDirection = layoutDirection,
        ) + offset.x
      drawTextWithHalo(
        textLayoutResult = textLayoutResult,
        topLeft = Offset(offsetX, offset.y),
        color = color,
        haloColor = haloColor,
        haloWidth = haloWidth.toPx(),
      )
    }
  }
}

public object ScaleBarDefaults {
  /** Default content color for light and dark basemaps. */
  public val ContentColor: Color = Color.Black.copy(alpha = 0.75f)

  /** Halo color that contrasts with [ContentColor]. */
  public val HaloColor: Color = Color.White.copy(alpha = 0.75f)

  public val ContentTextStyle: TextStyle = TextStyle(fontSize = 11.sp, color = ContentColor)

  public val HaloWidth: Dp = 1.dp

  public val BarWidth: Dp = 2.dp

  /** Uses the system measurement settings, with the user's locale as a fallback. */
  @Composable
  public fun measures(): ScaleBarMeasures {
    val region = Locale.current.region
    val system = systemDefaultPrimaryMeasure()
    return remember(system, region) {
      val primary = system ?: fallbackDefaultPrimaryMeasure(region)
      ScaleBarMeasures(primary = primary, secondary = defaultSecondaryMeasure(primary, region))
    }
  }
}

/** Finds the largest stop at or below [max], or the first stop if all are larger. */
private fun findStop(max: Length, stops: List<Length>): Length {
  val i = stops.binarySearch { it.compareTo(max) }
  // A negative result encodes the insertion point; select the preceding stop.
  return if (i >= 0) stops[i] else stops[(-i - 2).coerceAtLeast(0)]
}
