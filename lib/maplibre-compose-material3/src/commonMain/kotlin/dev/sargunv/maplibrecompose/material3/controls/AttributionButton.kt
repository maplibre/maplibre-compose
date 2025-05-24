package dev.sargunv.maplibrecompose.material3.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation.Url
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.material3.generated.Res
import dev.sargunv.maplibrecompose.material3.generated.attribution
import dev.sargunv.maplibrecompose.material3.generated.info
import dev.sargunv.maplibrecompose.material3.horizontal
import dev.sargunv.maplibrecompose.material3.reverse
import dev.sargunv.maplibrecompose.material3.toArrangement
import dev.sargunv.maplibrecompose.material3.vertical
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Info button from which an attribution popup text is expanded from. The attribution text retracts
 * once when the user first starts interacting with the map.
 *
 * @param cameraState Used to dismiss the attribution when the user interacts with the map.
 * @param styleState Used to get the attribution links to display.
 * @param modifier the Modifier to be applied to this layout node
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param iconColors Colors that will be used for the info button
 * @param textStyle Text style used for the attribution info
 * @param textLinkStyles Text link styles that should be used for the links in the attribution info
 * @param shape Shape of the attribution (applied to [Surface])
 * @param border Border of the attribution (applied to [Surface])
 * @param collapsedStyle Style of the attribution [Surface] when it is expanded
 * @param expandedStyle Style of the attribution [Surface] when it is collapsed
 */
@Composable
public fun AttributionButton(
  cameraState: CameraState,
  styleState: StyleState,
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.BottomEnd,
  iconColors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  textLinkStyles: TextLinkStyles? = null,
  shape: Shape = RoundedCornerShape(24.dp),
  border: BorderStroke? = null,
  expandedStyle: AttributionButtonStyle =
    AttributionButtonStyle(
      containerColor = MaterialTheme.colorScheme.surface,
      contentColor = contentColorFor(MaterialTheme.colorScheme.surface),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ),
  collapsedStyle: AttributionButtonStyle =
    AttributionButtonStyle(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
      contentColor = contentColorFor(MaterialTheme.colorScheme.surface).copy(alpha = 0f),
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
    ),
) {
  val attributions = styleState.sources.flatMap { it.attributionLinks }.distinct()
  if (attributions.isEmpty()) return

  val expanded = remember { MutableTransitionState(true) }

  LaunchedEffect(cameraState.isCameraMoving, cameraState.moveReason) {
    if (cameraState.isCameraMoving && cameraState.moveReason == CameraMoveReason.GESTURE) {
      expanded.targetState = false
    }
  }

  Box(modifier) {
    val surfaceColor by
      animateColorAsState(
        if (expanded.targetState) expandedStyle.containerColor else collapsedStyle.containerColor
      )
    val surfaceContentColor by
      animateColorAsState(
        if (expanded.targetState) expandedStyle.contentColor else collapsedStyle.contentColor
      )
    val surfaceTonalElevation by
      animateDpAsState(
        if (expanded.targetState) expandedStyle.tonalElevation else collapsedStyle.tonalElevation
      )
    val surfaceShadowElevation by
      animateDpAsState(
        if (expanded.targetState) expandedStyle.shadowElevation else collapsedStyle.shadowElevation
      )

    Surface(
      shape = shape,
      color = surfaceColor,
      contentColor = surfaceContentColor,
      tonalElevation = surfaceTonalElevation,
      shadowElevation = surfaceShadowElevation,
      border = border,
    ) {
      val rowArrangement = contentAlignment.horizontal.toArrangement()
      val originalLayoutDir = LocalLayoutDirection.current
      val buttonLayoutDir =
        if (rowArrangement == Arrangement.End) originalLayoutDir.reverse() else originalLayoutDir
      val expandAlignment =
        if (originalLayoutDir == LayoutDirection.Rtl) contentAlignment
        else contentAlignment.reverse()

      CompositionLocalProvider(LocalLayoutDirection provides buttonLayoutDir) {
        Row(
          horizontalArrangement = rowArrangement,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = { expanded.targetState = !expanded.targetState },
            colors = iconColors,
            modifier = Modifier.align(contentAlignment.vertical),
          ) {
            Icon(
              imageVector = vectorResource(Res.drawable.info),
              contentDescription = stringResource(Res.string.attribution),
            )
          }

          AnimatedVisibility(
            modifier = Modifier.align(Alignment.CenterVertically),
            visibleState = expanded,
            enter = expandIn(expandFrom = expandAlignment.horizontal + Alignment.CenterVertically),
            exit =
              shrinkOut(shrinkTowards = expandAlignment.horizontal + Alignment.CenterVertically),
          ) {
            ProvideTextStyle(value = textStyle) {
              FlowRow(
                modifier = Modifier.padding(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                attributions.forEachIndexed { i, attr ->
                  val attributionString = buildAnnotatedString {
                    val link = Url(url = attr.url, styles = textLinkStyles)
                    withLink(link) { this.append(attr.title) }
                  }
                  Text(attributionString)
                }
              }
            }
          }
        }
      }
    }
  }
}

@Immutable
public data class AttributionButtonStyle(
  /** Color of the attribution [Surface]. */
  public val containerColor: Color,

  /** Content Color of the attribution [Surface]. */
  public val contentColor: Color,

  /** Tonal Elevation of the attribution [Surface]. */
  public val tonalElevation: Dp,

  /** Shadow Elevation of the attribution [Surface]. */
  public val shadowElevation: Dp,
)
