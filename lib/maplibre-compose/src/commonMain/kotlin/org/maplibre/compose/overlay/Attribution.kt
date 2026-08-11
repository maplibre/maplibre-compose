package org.maplibre.compose.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandIn
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.digitalia.compose.htmlconverter.HtmlStyle
import be.digitalia.compose.htmlconverter.htmlToAnnotatedString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.generated.Res
import org.maplibre.compose.generated.attribution
import org.maplibre.compose.generated.info
import org.maplibre.compose.style.StyleState
import org.maplibre.compose.util.horizontal
import org.maplibre.compose.util.reverse
import org.maplibre.compose.util.toArrangement
import org.maplibre.compose.util.vertical

/**
 * Info button from which an attribution popup text is expanded. This version retracts when the user
 * interacts with the map.
 *
 * This component draws with Compose Foundation alone. The
 * [Material 3 module][org.maplibre.compose.material3] provides a themed version of it.
 *
 * @param cameraState Used to dismiss the attribution when the user interacts with the map.
 * @param styleState Used to get the attribution links to display.
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param toggleButton Composable that defines the button used to toggle the attribution display.
 *   Takes an onClick function parameter that should be called to switch states.
 * @param expandedContent Composable that defines how the attribution content is displayed when
 *   expanded. Takes the list of HTML strings to display and the resolved text style.
 * @param expandedStyle Style of the attribution container when it is expanded.
 * @param collapsedStyle Style of the attribution container when it is collapsed.
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
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionDefaults.button,
  expandedContent: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    AttributionDefaults.content,
  expandedStyle: AttributionStyle = AttributionDefaults.expandedStyle(),
  collapsedStyle: AttributionStyle = AttributionDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionDefaults.collapse,
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
    collapsedStyle = collapsedStyle,
    expand = expand,
    collapse = collapse,
  )
}

/**
 * Info button from which an attribution popup text is expanded. This version allows the caller to
 * manage the state.
 *
 * This component draws with Compose Foundation alone. The
 * [Material 3 module][org.maplibre.compose.material3] provides a themed version of it.
 *
 * @param expanded Whether the attribution text is expanded.
 * @param onClick Called when the button is pressed. Should toggle the expanded state.
 * @param styleState Used to get the attribution links to display.
 * @param contentAlignment Will be used to determine layout of the attribution icon and text.
 * @param toggleButton Composable that defines the button used to toggle the attribution display.
 *   Takes an onClick function parameter that should be called to switch states.
 * @param expandedContent Composable that defines how the attribution content is displayed when
 *   expanded. Takes the list of HTML strings to display and the resolved text style.
 * @param expandedStyle Style of the attribution container when it is expanded.
 * @param collapsedStyle Style of the attribution container when it is collapsed.
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
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionDefaults.button,
  expandedContent: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    AttributionDefaults.content,
  expandedStyle: AttributionStyle = AttributionDefaults.expandedStyle(),
  collapsedStyle: AttributionStyle = AttributionDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionDefaults.collapse,
) {
  val attributions by remember { derivedStateOf { styleState.attributions() } }
  if (attributions.isEmpty()) return

  val style = if (expanded) expandedStyle else collapsedStyle
  val containerColor by animateColorAsState(style.containerColor)
  val contentColor by animateColorAsState(style.contentColor)
  val shadowElevation by animateDpAsState(style.shadowElevation)

  Box(
    modifier
      .shadow(shadowElevation, style.shape, clip = false)
      .then(style.border?.let { Modifier.border(it, style.shape) } ?: Modifier)
      .background(containerColor, style.shape)
      .clip(style.shape)
      // Absorb gestures that land on the container, so that a drag across the attribution text
      // does not pan the map underneath it. Material's Surface does the same, and groups its
      // contents for screen reader traversal.
      .pointerInput(Unit) {}
      .semantics { isTraversalGroup = true }
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
              expandedContent(attributions, style.textStyle.copy(color = contentColor))
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
 * @param textStyle Style of the attribution text.
 * @param linkStyles Optional style for hyperlinks. Default is underlined.
 * @param spacing The horizontal spacing between items in the flow layout.
 * @param breakWithinAttribution Whether the text within an individual attribution should break
 *   lines or scroll horizontally. Line breaks may still be inserted between attributions even when
 *   this is `true`.
 */
@Composable
public fun AttributionLinks(
  attributions: List<String>,
  modifier: Modifier = Modifier,
  textStyle: TextStyle = AttributionDefaults.ContentTextStyle,
  linkStyles: TextLinkStyles? = AttributionDefaults.LinkStyles,
  spacing: Dp = 8.dp,
  breakWithinAttribution: Boolean = false,
) {
  val texts =
    remember(attributions, linkStyles) {
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
      if (breakWithinAttribution) BasicText(it, style = textStyle)
      else
        BasicText(
          it,
          style = textStyle,
          maxLines = 1,
          modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
  }
}

/**
 * The distinct attribution texts of every source in the style, in the order that the style declares
 * them. Sources that declare no attribution are skipped.
 */
public fun StyleState.attributions(): List<String> =
  sources.values.map { it.attributionHtml }.filter { it.isNotEmpty() }.distinct()

public object AttributionDefaults {
  /** Reads over both light and dark basemaps, in the absence of a theme to draw colors from. */
  public val ContainerColor: Color = Color.White.copy(alpha = 0.75f)

  public val ContentColor: Color = Color.Black.copy(alpha = 0.75f)

  public val ContentTextStyle: TextStyle = TextStyle(fontSize = 12.sp, color = ContentColor)

  public val LinkStyles: TextLinkStyles =
    TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))

  /** Accessibility label for the button that toggles the attribution text. */
  @Composable public fun contentDescription(): String = stringResource(Res.string.attribution)

  /** The info icon that the toggle button draws. */
  @Composable public fun icon(): Painter = painterResource(Res.drawable.info)

  public val button: @Composable (onClick: () -> Unit) -> Unit = { onClick ->
    Box(
      Modifier.size(40.dp)
        .clip(RoundedCornerShape(percent = 50))
        .clickable(onClick = onClick, role = Role.Button),
      contentAlignment = Alignment.Center,
    ) {
      Image(
        painter = icon(),
        contentDescription = contentDescription(),
        modifier = Modifier.size(24.dp),
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(ContentColor),
      )
    }
  }

  public val content: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    { attributions, textStyle ->
      AttributionLinks(attributions, textStyle = textStyle)
    }

  public fun expandedStyle(): AttributionStyle =
    AttributionStyle(containerColor = ContainerColor, contentColor = ContentColor)

  /** Only the toggle button stays visible, so the container and its text fade out entirely. */
  public fun collapsedStyle(): AttributionStyle =
    AttributionStyle(
      containerColor = ContainerColor.copy(alpha = 0f),
      contentColor = ContentColor.copy(alpha = 0f),
    )

  public val expand: (Alignment) -> EnterTransition = { expandIn(expandFrom = it) }

  public val collapse: (Alignment) -> ExitTransition = { shrinkOut(shrinkTowards = it) }
}

@Immutable
public data class AttributionStyle(
  /** Color of the attribution container. */
  public val containerColor: Color,

  /** Color of the attribution text. */
  public val contentColor: Color,

  /** Style of the attribution text. [contentColor] overrides its color. */
  public val textStyle: TextStyle = AttributionDefaults.ContentTextStyle,

  /** Shadow elevation of the attribution container. */
  public val shadowElevation: Dp = 0.dp,

  /** Shape of the attribution container. */
  public val shape: Shape = RoundedCornerShape(24.dp),

  /** Border of the attribution container. */
  public val border: BorderStroke? = null,
)
