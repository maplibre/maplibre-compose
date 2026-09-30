package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.map.MapState
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleSnapshot

internal class FeatureClickDispatcher(
  private val state: MapState,
  private val desiredRevision: State<State<StyleSnapshot?>>,
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

    fun current(node: StyleSnapshot.Layer): StyleSnapshot.Layer? =
      desiredRevision.value.value?.layers?.firstOrNull {
        it.definition.id == node.definition.id && it.registration === node.registration
      }

    return ClickPath(::valid) { event ->
      if (!valid()) return@ClickPath ClickResult.Consume
      // The published handles carry the engine's layer order, so a tap makes no engine call. They
      // appear one owner read after the style loads; a tap in that window waits for them rather
      // than reading the unpublished set as an empty layer stack. The wait is lease-bound, so a
      // presentation that ends first drops the tap instead of parking the dispatcher.
      if (nodes.isNotEmpty()) {
        attachment.runLeaseBound { state.style.awaitLoaded() }
        if (!valid()) return@ClickPath ClickResult.Consume
      }
      val layerIds =
        if (nodes.isNotEmpty() && style?.isLoaded == true) state.style.layerHandles().keys.toList()
        else emptyList()
      val targets =
        layerIds.asReversed().mapNotNull { id ->
          nodes[id]?.takeIf { current(it)?.handler(family) != null }
        }
      if (targets.isEmpty()) return@ClickPath unhandled(family, event)

      // Only layers with a handler for this click are queried, all in one engine visit.
      val hits =
        attachment.queryRenderedFeaturesByLayer(
          event.screenOffset,
          targets.associate { it.definition.id to it.hitPadding },
        )

      for (node in targets) {
        // A query suspends, and a handler can change the map: check again before entering app code.
        if (!valid()) return@ClickPath ClickResult.Consume
        val handler = current(node)?.handler(family) ?: continue
        val features = hits[node.definition.id].orEmpty()
        if (features.isEmpty()) continue
        if (event.handler(features).consumed) return@ClickPath ClickResult.Consume
      }
      if (!valid()) return@ClickPath ClickResult.Consume

      unhandled(family, event)
    }
  }

  private fun unhandled(family: TapFamily, event: ClickEvent): ClickResult =
    if (family == TapFamily.Tap)
      interactions.value.callbacks.unhandledClick?.invoke(event) ?: ClickResult.Pass
    else ClickResult.Pass
}

private fun StyleSnapshot.Layer.handler(family: TapFamily): FeaturesClickHandler? =
  when (family) {
    TapFamily.Tap -> onClick
    TapFamily.DoubleTap -> onDoubleClick
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> onLongClick
    TapFamily.TwoFingerTap -> null
  }
