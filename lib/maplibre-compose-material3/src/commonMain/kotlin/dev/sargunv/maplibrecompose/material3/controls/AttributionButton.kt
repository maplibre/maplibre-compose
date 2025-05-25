package dev.sargunv.maplibrecompose.material3.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.sargunv.maplibrecompose.compose.CameraState
import dev.sargunv.maplibrecompose.core.CameraMoveReason
import dev.sargunv.maplibrecompose.core.source.AttributionLink
import dev.sargunv.maplibrecompose.material3.Reverse
import dev.sargunv.maplibrecompose.material3.generated.Res
import dev.sargunv.maplibrecompose.material3.generated.attribution
import dev.sargunv.maplibrecompose.material3.generated.info
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * Info button from which an attribution popup text is expanded from. The attribution text retracts
 * once when the user first starts interacting with the map.
 *
 * @param lastCameraMoveReason The reason reason why the camera moved, last time it moved. See
 *   [CameraState.moveReason].
 * @param attributions List of attributions to show. See
 *   [Source.attributionLinks][dev.sargunv.maplibrecompose.core.source.Source.attributionLinks] via
 *   [StyleState.sources][dev.sargunv.maplibrecompose.compose.StyleState.sources]
 * @param modifier the Modifier to be applied to this layout node
 * @param iconColors Colors that will be used for the info button
 * @param textStyle Text style used for the attribution info
 * @param textLinkStyles Text link styles that should be used for the links in the attribution info
 * @param popupEndPadding Padding that will be applied on the end of the popup (i.e. if the popup is
 *   aligned to the start, padding will be applied on the end, but if the popup is aligned to the
 *   end, padding will be applied to the start)
 * @param popupShape Shape of the popup (applied to [Surface])
 * @param popupColor Color of the popup (applied to [Surface])
 * @param popupContentColor Content Color of the popup (applied to [Surface])
 * @param popupTonalElevation Tonal Elevation of the popup (applied to [Surface])
 * @param popupShadowElevation Shadow Elevation of the popup (applied to [Surface])
 * @param popupBorder Border of the popup (applied to [Surface])
 */
@Composable
public fun AttributionButton(
  lastCameraMoveReason: CameraMoveReason,
  attributions: List<AttributionLink>,
  modifier: Modifier = Modifier,
  iconColors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  textLinkStyles: TextLinkStyles? = null,
  popupEndPadding: Dp = 0.dp,
  popupShape: Shape = RoundedCornerShape(24.dp),
  popupColor: Color = MaterialTheme.colorScheme.surface,
  popupContentColor: Color = contentColorFor(popupColor),
  popupTonalElevation: Dp = 0.dp,
  popupShadowElevation: Dp = 0.dp,
  popupBorder: BorderStroke? = null,
) {
  if (attributions.isEmpty()) return

  val expanded = remember { MutableTransitionState(true) }

  // close popup on moving the map
  LaunchedEffect(lastCameraMoveReason) {
    if (lastCameraMoveReason == CameraMoveReason.GESTURE) {
      expanded.targetState = false
    }
  }

  Box(modifier) {
    IconButton(onClick = { expanded.targetState = !expanded.currentState }, colors = iconColors) {
      InfoIcon()
    }

    if (expanded.currentState || expanded.targetState) {
      // popup position provider places popup superimposed over the icon button, the arrangement
      // and alignment of its content depends on the position of the icon button in relation to the
      // screen.
      val density = LocalDensity.current
      var alignLeft by remember { mutableStateOf(false) }
      var alignTop by remember { mutableStateOf(false) }
      var maxPopupWidth by remember { mutableStateOf(Dp.Unspecified) }
      val popupPositionProvider = SuperimposingPopupPositionProvider { left, top, maxWidth ->
        alignLeft = left
        alignTop = top
        maxPopupWidth = with(density) { maxWidth.toDp() }
      }
      val verticalAlignment = if (alignTop) Alignment.Top else Alignment.Bottom
      val horizontalArrangement =
        if (alignLeft) Arrangement.Absolute.Left else Arrangement.Absolute.Reverse
      val textAlign = if (alignLeft) TextAlign.Left else TextAlign.Right
      val attributionAlignment = if (alignLeft) AbsoluteAlignment.Left else AbsoluteAlignment.Right

      Popup(
        popupPositionProvider = popupPositionProvider,
        properties = PopupProperties(clippingEnabled = false),
        onDismissRequest = { expanded.targetState = false },
      ) {
        AnimatedVisibility(
          modifier =
            Modifier.widthIn(max = maxPopupWidth).paddingEndOfPopup(popupEndPadding, alignLeft),
          visibleState = expanded,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          Surface(
            shape = popupShape,
            color = popupColor,
            contentColor = popupContentColor,
            tonalElevation = popupTonalElevation,
            shadowElevation = popupShadowElevation,
            border = popupBorder,
          ) {
            // the content of the popup should be aligned centered vertically in general, only the
            // icon button should be in the corner, so that it exactly overlaps the original button
            Row(
              horizontalArrangement = horizontalArrangement,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(
                onClick = { expanded.targetState = false },
                colors = iconColors,
                modifier = Modifier.align(verticalAlignment),
              ) {
                InfoIcon()
              }
              AttributionTexts(
                attributions = attributions,
                textStyle = textStyle,
                textLinkStyles = textLinkStyles,
                textAlign = textAlign,
                attributionAlignment = attributionAlignment,
                modifier = Modifier.padding(8.dp),
              )
              // icon buttons are automatically padded to have a certain click size, which makes the
              // popup appear misaligned if we don't also add some extra padding on the other side
              Spacer(Modifier.width(16.dp))
            }
          }
        }
      }
    }
  }
}

private fun Modifier.paddingEndOfPopup(padding: Dp, alignLeft: Boolean) =
  if (alignLeft) absolutePadding(right = padding) else absolutePadding(left = padding)

@Composable
private fun InfoIcon(modifier: Modifier = Modifier) {
  Icon(
    imageVector = vectorResource(Res.drawable.info),
    contentDescription = stringResource(Res.string.attribution),
    modifier = modifier,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributionTexts(
  attributions: List<AttributionLink>,
  textStyle: TextStyle,
  textLinkStyles: TextLinkStyles?,
  textAlign: TextAlign,
  attributionAlignment: Alignment.Horizontal,
  modifier: Modifier = Modifier,
) {
  ProvideTextStyle(textStyle) {
    FlowRow(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(8.dp, attributionAlignment),
    ) {
      attributions.distinct().forEach {
        val attributionString = buildAnnotatedString {
          val link = LinkAnnotation.Url(url = it.url, styles = textLinkStyles)
          withLink(link) { append(it.title) }
        }
        Text(text = attributionString, textAlign = textAlign)
      }
    }
  }
}
