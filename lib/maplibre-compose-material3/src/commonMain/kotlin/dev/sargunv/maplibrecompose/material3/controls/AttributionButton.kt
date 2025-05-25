package dev.sargunv.maplibrecompose.material3.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation.Url
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.compose.StyleState
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.source.AttributionLink
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
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param toggleButton Composable lambda that defines the button used to toggle the attribution
 *   display. Takes a toggle function parameter that should be called to switch states.
 * @param expandedContent Composable lambda that defines how the attribution content is displayed
 *   when expanded. Takes a list of AttributionLink as parameter.
 * @param expandedStyle Style of the attribution [Surface] when it is expanded
 * @param collapsedStyle Style of the attribution [Surface] when it is collapsed
 * @param expand Function that returns an [EnterTransition] for the expanding animation based on the
 *   given alignment
 * @param collapse Function that returns an [ExitTransition] for the collapsing animation based on
 *   the given alignment
 */
@Composable
public fun ExpandingAttributionButton(
  cameraState: CameraState,
  styleState: StyleState,
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.BottomEnd,
  toggleButton: @Composable (toggle: () -> Unit) -> Unit = AttributionButtonDefaults.button,
  expandedContent: @Composable (List<AttributionLink>) -> Unit = AttributionButtonDefaults.content,
  expandedStyle: AttributionButtonStyle = AttributionButtonDefaults.expandedStyle(),
  collapsedStyle: AttributionButtonStyle = AttributionButtonDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionButtonDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionButtonDefaults.collapse,
) {
  val attributions = styleState.sources.flatMap { it.attributionLinks }.distinct()
  if (attributions.isEmpty()) return

  val expanded = remember { MutableTransitionState(true) }
  OnCameraMoveEffect(cameraState) { expanded.targetState = false }

  Surface(
    modifier = modifier,
    shape = if (expanded.targetState) expandedStyle.shape else collapsedStyle.shape,
    border = if (expanded.targetState) expandedStyle.border else collapsedStyle.border,
    color =
      animateColorAsState(
          if (expanded.targetState) expandedStyle.containerColor else collapsedStyle.containerColor
        )
        .value,
    contentColor =
      animateColorAsState(
          if (expanded.targetState) expandedStyle.contentColor else collapsedStyle.contentColor
        )
        .value,
    tonalElevation =
      animateDpAsState(
          if (expanded.targetState) expandedStyle.tonalElevation else collapsedStyle.tonalElevation
        )
        .value,
    shadowElevation =
      animateDpAsState(
          if (expanded.targetState) expandedStyle.shadowElevation
          else collapsedStyle.shadowElevation
        )
        .value,
  ) {
    val layoutDir = LocalLayoutDirection.current

    val animationAlignment =
      Alignment.CenterVertically +
        (if (layoutDir == LayoutDirection.Rtl) contentAlignment else contentAlignment.reverse())
          .horizontal

    val rowArrangement = contentAlignment.horizontal.toArrangement()

    CompositionLocalProvider(
      LocalLayoutDirection provides
        if (rowArrangement == Arrangement.End) layoutDir.reverse() else layoutDir
    ) {
      Row(horizontalArrangement = rowArrangement, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.align(contentAlignment.vertical)) {
          toggleButton { expanded.targetState = !expanded.targetState }
        }

        AnimatedVisibility(
          visibleState = expanded,
          modifier = Modifier.align(Alignment.CenterVertically),
          enter = expand(animationAlignment),
          exit = collapse(animationAlignment),
        ) {
          Box(Modifier.padding(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            expandedContent(attributions)
          }
        }
      }
    }
  }
}

@Composable
private fun OnCameraMoveEffect(cameraState: CameraState, onCameraMove: () -> Unit) {
  LaunchedEffect(cameraState.isCameraMoving, cameraState.moveReason) {
    if (cameraState.isCameraMoving && cameraState.moveReason == CameraMoveReason.GESTURE) {
      onCameraMove()
    }
  }
}

@Composable
public fun AttributionLinks(
  attributions: List<AttributionLink>,
  linkStyles: TextLinkStyles? = null,
  spacing: Dp = 8.dp,
  modifier: Modifier = Modifier,
) {
  FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
    attributions.forEachIndexed { i, attr ->
      val attributionString = buildAnnotatedString {
        val link = Url(url = attr.url, styles = linkStyles)
        withLink(link) { this.append(attr.title) }
      }
      Text(attributionString)
    }
  }
}

public object AttributionButtonDefaults {
  public val button: @Composable (toggle: () -> Unit) -> Unit = { toggle ->
    IconButton(
      onClick = toggle,
      colors =
        IconButtonDefaults.iconButtonColors()
          .copy(contentColor = contentColorFor(MaterialTheme.colorScheme.surface)),
    ) {
      Icon(
        imageVector = vectorResource(Res.drawable.info),
        contentDescription = stringResource(Res.string.attribution),
      )
    }
  }

  public val content: @Composable (List<AttributionLink>) -> Unit = {
    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { AttributionLinks(it) }
  }

  @Composable
  public fun expandedStyle(): AttributionButtonStyle =
    AttributionButtonStyle(
      containerColor = MaterialTheme.colorScheme.surface,
      contentColor = contentColorFor(MaterialTheme.colorScheme.surface),
    )

  @Composable
  public fun collapsedStyle(): AttributionButtonStyle =
    AttributionButtonStyle(
      containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
      contentColor = contentColorFor(MaterialTheme.colorScheme.surface).copy(alpha = 0f),
    )

  public val expand: (Alignment) -> EnterTransition = { expandIn(expandFrom = it) }

  public val collapse: (Alignment) -> ExitTransition = { shrinkOut(shrinkTowards = it) }
}

@Immutable
public data class AttributionButtonStyle(
  /** Color of the attribution [Surface]. */
  public val containerColor: Color,

  /** Content Color of the attribution [Surface]. */
  public val contentColor: Color,

  /** Tonal Elevation of the attribution [Surface]. */
  public val tonalElevation: Dp = 0.dp,

  /** Shadow Elevation of the attribution [Surface]. */
  public val shadowElevation: Dp = 0.dp,

  /** Shape of the attribution [Surface]. */
  public val shape: Shape = RoundedCornerShape(24.dp),

  /** Borner of the attribution [Surface]. */
  val border: BorderStroke? = null,
)
