package org.maplibre.compose.material3

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.overlay.AttributionDefaults
import org.maplibre.compose.overlay.AttributionLinks as BaseAttributionLinks
import org.maplibre.compose.overlay.AttributionStyle
import org.maplibre.compose.overlay.ExpandingAttributionButton as BaseExpandingAttributionButton
import org.maplibre.compose.style.StyleState

/**
 * Info button from which an attribution popup text is expanded. This version retracts when the user
 * interacts with the map.
 *
 * This is [org.maplibre.compose.overlay.ExpandingAttributionButton] with its colors, typography,
 * and widgets taken from the Material 3 theme.
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
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionButtonDefaults.button,
  expandedContent: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    AttributionButtonDefaults.content,
  expandedStyle: AttributionStyle = AttributionButtonDefaults.expandedStyle(),
  collapsedStyle: AttributionStyle = AttributionButtonDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionDefaults.collapse,
) {
  BaseExpandingAttributionButton(
    cameraState = cameraState,
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
 * This is [org.maplibre.compose.overlay.ExpandingAttributionButton] with its colors, typography,
 * and widgets taken from the Material 3 theme.
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
  toggleButton: @Composable (onClick: () -> Unit) -> Unit = AttributionButtonDefaults.button,
  expandedContent: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    AttributionButtonDefaults.content,
  expandedStyle: AttributionStyle = AttributionButtonDefaults.expandedStyle(),
  collapsedStyle: AttributionStyle = AttributionButtonDefaults.collapsedStyle(),
  expand: (Alignment) -> EnterTransition = AttributionDefaults.expand,
  collapse: (Alignment) -> ExitTransition = AttributionDefaults.collapse,
) {
  BaseExpandingAttributionButton(
    expanded = expanded,
    onClick = onClick,
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
 * A composable function that displays a collection of attribution links as a flow layout, styled
 * from the Material 3 theme.
 *
 * @param attributions A list of HTML strings representing the attributions that need to be
 *   displayed as links. See: [org.maplibre.compose.sources.Source.attributionHtml].
 * @param textStyle Style of the attribution text.
 * @param linkStyles Optional style for hyperlinks. Default is primary color and underlined.
 * @param spacing The horizontal spacing between items in the flow layout.
 * @param breakWithinAttribution Whether the text within an individual attribution should break
 *   lines or scroll horizontally. Line breaks may still be inserted between attributions even when
 *   this is `true`.
 */
@Composable
public fun AttributionLinks(
  attributions: List<String>,
  modifier: Modifier = Modifier,
  textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  linkStyles: TextLinkStyles? = AttributionButtonDefaults.linkStyles(),
  spacing: Dp = 8.dp,
  breakWithinAttribution: Boolean = false,
) {
  BaseAttributionLinks(
    attributions = attributions,
    modifier = modifier,
    textStyle = textStyle,
    linkStyles = linkStyles,
    spacing = spacing,
    breakWithinAttribution = breakWithinAttribution,
  )
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
        painter = AttributionDefaults.icon(),
        contentDescription = AttributionDefaults.contentDescription(),
      )
    }
  }

  public val content: @Composable (attributions: List<String>, textStyle: TextStyle) -> Unit =
    { attributions, textStyle ->
      AttributionLinks(attributions, textStyle = textStyle)
    }

  /**
   * @param tonalElevation Resolved into the container color, because the base component draws no
   *   Material surface of its own.
   */
  @Composable
  public fun expandedStyle(
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
  ): AttributionStyle =
    AttributionStyle(
      containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(tonalElevation),
      contentColor = contentColorFor(MaterialTheme.colorScheme.surface),
      textStyle = MaterialTheme.typography.bodyMedium,
      shadowElevation = shadowElevation,
    )

  /** @see expandedStyle */
  @Composable
  public fun collapsedStyle(
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
  ): AttributionStyle =
    expandedStyle(tonalElevation, shadowElevation).let {
      it.copy(
        containerColor = it.containerColor.copy(alpha = 0f),
        contentColor = it.contentColor.copy(alpha = 0f),
      )
    }

  @Composable
  public fun linkStyles(): TextLinkStyles =
    TextLinkStyles(
      style =
        SpanStyle(
          color = MaterialTheme.colorScheme.primary,
          textDecoration = TextDecoration.Underline,
        )
    )
}
