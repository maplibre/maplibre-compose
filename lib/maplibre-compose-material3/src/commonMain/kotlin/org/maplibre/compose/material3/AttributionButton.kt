package org.maplibre.compose.material3

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import be.digitalia.compose.htmlconverter.HtmlStyle
import be.digitalia.compose.htmlconverter.htmlToAnnotatedString
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.material3.generated.Res
import org.maplibre.compose.material3.generated.attribution
import org.maplibre.compose.material3.generated.info
import org.maplibre.compose.material3.util.horizontal
import org.maplibre.compose.material3.util.reverse
import org.maplibre.compose.material3.util.toArrangement
import org.maplibre.compose.material3.util.vertical
import org.maplibre.compose.style.StyleState

/**
 * Info button from which an attribution popup text is expanded. This version retracts when the user
 * interacts with the map.
 *
 * @param cameraState Used to dismiss the attribution when the user interacts with the map.
 * @param styleState Used to get the attribution links to display.
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param toggleButton Composable that defines the button used to toggle the attribution display.
 *   Takes an onClick function parameter that should be called to switch states.
 * @param expandedContent Composable that defines how the attribution content is displayed when
 *   expanded. Takes a list of HTML strings as a parameter.
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
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionButtonDefaults.button,
  expandedContent: @Composable (List<String>) -> Unit = AttributionButtonDefaults.content,
  expandedStyle: AttributionButtonStyle = AttributionButtonDefaults.expandedStyle(),
  collapsedStyle: AttributionButtonStyle = AttributionButtonDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionButtonDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionButtonDefaults.collapse,
) {
  var expanded by remember { mutableStateOf(true) }

  // dismiss on any map gesture
  LaunchedEffect(cameraState.isCameraMoving, cameraState.moveReason) {
    if (cameraState.isCameraMoving && cameraState.moveReason == CameraMoveReason.GESTURE) {
      expanded = false
    }
  }

  ExpandingAttributionButton(
    expanded = expanded,
    onClick = { expanded = !expanded },
    styleState = styleState,
    modifier = modifier,
    contentAlignment = contentAlignment,
    toggleButton = toggleButton,
    expandedContent = expandedContent,
    expandedStyle = expandedStyle,
  )
}

/**
 * Info button from which an attribution popup text is expanded. This version allows the caller to
 * manage the state.
 *
 * @param expanded Whether the attribution text is expanded.
 * @param onClick Called when the button is pressed. Should toggle the expanded state.
 * @param styleState Used to get the attribution links to display.
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param toggleButton Composable that defines the button used to toggle the attribution display.
 *   Takes an onClick function parameter that should be called to switch states.
 * @param expandedContent Composable that defines how the attribution content is displayed when
 *   expanded. Takes a list of HTML strings as a parameter.
 * @param expandedStyle Style of the attribution [Surface] when it is expanded
 * @param collapsedStyle Style of the attribution [Surface] when it is collapsed
 * @param expand Function that returns an [EnterTransition] for the expanding animation based on the
 *   given alignment
 * @param collapse Function that returns an [ExitTransition] for the collapsing animation based on
 *   the given alignment
 */
@Composable
public fun ExpandingAttributionButton(
  expanded: Boolean,
  onClick: () -> Unit,
  styleState: StyleState,
  modifier: Modifier = Modifier,
  contentAlignment: Alignment = Alignment.BottomEnd,
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionButtonDefaults.button,
  expandedContent: @Composable (List<String>) -> Unit = AttributionButtonDefaults.content,
  expandedStyle: AttributionButtonStyle = AttributionButtonDefaults.expandedStyle(),
  collapsedStyle: AttributionButtonStyle = AttributionButtonDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionButtonDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionButtonDefaults.collapse,
) {
  val attributions by remember {
    derivedStateOf {
      styleState.sources.values.map { it.attributionHtml }.filter { it.isNotEmpty() }.distinct()
    }
  }
  if (attributions.isEmpty()) return

  Surface(
    modifier = modifier,
    shape = if (expanded) expandedStyle.shape else collapsedStyle.shape,
    border = if (expanded) expandedStyle.border else collapsedStyle.border,
    color =
      animateColorAsState(
          if (expanded) expandedStyle.containerColor else collapsedStyle.containerColor
        )
        .value,
    contentColor =
      animateColorAsState(if (expanded) expandedStyle.contentColor else collapsedStyle.contentColor)
        .value,
    tonalElevation =
      animateDpAsState(
          if (expanded) expandedStyle.tonalElevation else collapsedStyle.tonalElevation
        )
        .value,
    shadowElevation =
      animateDpAsState(
          if (expanded) expandedStyle.shadowElevation else collapsedStyle.shadowElevation
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
        Box(Modifier.align(contentAlignment.vertical)) { toggleButton(onClick) }

        AnimatedVisibility(
          visible = expanded,
          modifier = Modifier.align(Alignment.CenterVertically),
          enter = expand(animationAlignment),
          exit = collapse(animationAlignment),
        ) {
          Box(Modifier.padding(start = 0.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
              expandedContent(attributions)
            }
          }
        }
      }
    }
  }
}

/**
 * A composable function that displays a collection of attribution links as a flow layout.
 *
 * @param attributions A list of HTML strings representing the attributions that need to be
 *   displayed as links. See: [org.maplibre.compose.sources.Source.attributionHtml].
 * @param linkStyles Optional style for hyperlinks. Default is primary color and underlined.
 * @param spacing The horizontal spacing between items in the flow layout.
 * @param breakWithinAttribution Whether the text within an individual attribution should break
 *   lines or scroll horizontally. Line breaks may still be inserted between attributions even when
 *   this is `true`.
 */
@Composable
public fun AttributionLinks(
  attributions: List<String>,
  linkStyles: TextLinkStyles? = AttributionButtonDefaults.linkStyles(),
  spacing: Dp = 8.dp,
  breakWithinAttribution: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val texts =
    remember(attributions) {
      attributions.map { html ->
        htmlToAnnotatedString(
          html = html,
          compactMode = true,
          style = HtmlStyle(indentUnit = TextUnit.Unspecified, textLinkStyles = linkStyles),
        )
      }
    }
  FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
    texts.forEach {
      if (breakWithinAttribution) Text(it)
      else Text(it, maxLines = 1, modifier = Modifier.horizontalScroll(rememberScrollState()))
    }
  }
}

public object AttributionButtonDefaults {
  public val button: @Composable (onClick: () -> Unit) -> Unit = { onClick ->
    IconButton(
      onClick = onClick,
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

  public val content: @Composable (List<String>) -> Unit = {
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

  @Composable
  public fun linkStyles(): TextLinkStyles =
    TextLinkStyles(
      style =
        SpanStyle(
          color = MaterialTheme.colorScheme.primary,
          textDecoration = TextDecoration.Underline,
        )
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
