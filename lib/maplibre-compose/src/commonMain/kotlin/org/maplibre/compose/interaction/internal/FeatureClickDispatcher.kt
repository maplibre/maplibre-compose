package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.interaction.TapEvent
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding

/**
 * Layer knowledge stays outside input recognition; presses capture subscriptions; recognized clicks
 * read loaded order.
 */
internal class FeatureClickDispatcher(
  private val state: MapState,
  private val desiredRevision: State<State<DesiredStyleRevision?>>,
  private val loadedStyle: State<StyleBinding?>,
  private val interactions: State<MapInteractions>,
  private val subscriptions: InteractionSubscriptions,
) {
  fun capture(family: TapFamily): ClickPath? {
    val attachment = state.currentMapAttachment ?: return null
    val style = loadedStyle.value
    val structure = interactions.value.structuralKey
    val unhandledSlot = subscriptions.unhandledClick.capture().takeIf { family == TapFamily.Tap }
    val nodes =
      desiredRevision.value.value
        ?.layers
        ?.filter { it.handler(family) != null }
        ?.associateBy { it.definition.id }
        .orEmpty()

    // Callback replacement keeps a press alive; replacing its attachment or style does not.
    fun valid(): Boolean =
      interactions.value.structuralKey == structure &&
        !state.isClosed &&
        attachment.isValid &&
        state.currentMapAttachment === attachment &&
        loadedStyle.value === style &&
        (style == null || style.isLoaded)

    fun current(node: DesiredStyleLayer): DesiredStyleLayer? =
      desiredRevision.value.value?.layers?.firstOrNull {
        it.definition.id == node.definition.id &&
          it.registration === node.registration &&
          it.subscription(family) === node.subscription(family)
      }

    return ClickPath(::valid, nodes.isNotEmpty() || unhandledSlot != null) { event ->
      if (!valid()) return@ClickPath ClickResult.Consume
      val layerIds = style?.takeIf { nodes.isNotEmpty() && it.isLoaded }?.layerIds().orEmpty()

      for (id in layerIds.asReversed()) {
        val node = nodes[id] ?: continue
        if (current(node)?.handler(family) == null) continue

        val offset = event.screenOffset
        val padding = node.hitPadding
        val features =
          if (padding == 0.dp) attachment.queryRenderedFeatures(offset, setOf(node.definition.id))
          else
            attachment.queryRenderedFeatures(
              DpRect(
                offset.x - padding,
                offset.y - padding,
                offset.x + padding,
                offset.y + padding,
              ),
              setOf(node.definition.id),
            )

        // A query suspends: resolve the current handler again before entering app code.
        if (!valid()) return@ClickPath ClickResult.Consume
        val handler = current(node)?.handler(family) ?: continue
        if (features.isNotEmpty() && handler(features).consumed)
          return@ClickPath ClickResult.Consume
        if (!valid()) return@ClickPath ClickResult.Consume
      }

      if (subscriptions.unhandledClick.contains(unhandledSlot))
        interactions.value.callbacks.unhandledClick?.invoke(event as TapEvent) ?: ClickResult.Pass
      else ClickResult.Pass
    }
  }
}

private fun DesiredStyleLayer.handler(family: TapFamily): FeaturesClickHandler? =
  when (family) {
    TapFamily.Tap -> onClick
    TapFamily.DoubleTap -> onDoubleClick
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> onLongClick
    TapFamily.TwoFingerTap -> null
  }

private fun DesiredStyleLayer.subscription(family: TapFamily): Any? =
  when (family) {
    TapFamily.Tap -> clickSubscription
    TapFamily.DoubleTap -> doubleClickSubscription
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> longClickSubscription
    TapFamily.TwoFingerTap -> null
  }
