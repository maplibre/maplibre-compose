package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.DesiredStyleLayer
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.StyleBinding

internal class FeatureClickDispatcher(
  private val state: MapState,
  private val desiredRevision: State<State<DesiredStyleRevision?>>,
  private val loadedStyle: State<StyleBinding?>,
  private val interactions: State<MapInteractions>,
) {
  fun hasHandlers(family: TapFamily): Boolean =
    desiredRevision.value.value?.layers?.any { it.handler(family) != null } == true ||
      (family == TapFamily.Tap && interactions.value.callbacks.unhandledClick != null)

  fun capture(family: TapFamily): ClickPath? {
    val attachment = state.currentMapAttachment ?: return null
    val style = loadedStyle.value
    val nodes =
      desiredRevision.value.value
        ?.layers
        ?.filter { it.handler(family) != null }
        ?.associateBy { it.definition.id }
        .orEmpty()

    // The same click must not query a replacement map or style.
    fun valid(): Boolean =
      !state.isClosed &&
        attachment.isValid &&
        state.currentMapAttachment === attachment &&
        loadedStyle.value === style &&
        (style == null || style.isLoaded)

    fun current(node: DesiredStyleLayer): DesiredStyleLayer? =
      desiredRevision.value.value?.layers?.firstOrNull {
        it.definition.id == node.definition.id && it.registration === node.registration
      }

    return ClickPath(::valid) { event ->
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

      if (family == TapFamily.Tap)
        interactions.value.callbacks.unhandledClick?.invoke(event) ?: ClickResult.Pass
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
