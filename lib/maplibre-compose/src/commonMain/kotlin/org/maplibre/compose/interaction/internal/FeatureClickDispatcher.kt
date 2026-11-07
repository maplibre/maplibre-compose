package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.FeatureClickEvent
import org.maplibre.compose.interaction.FeatureClickHandler
import org.maplibre.compose.interaction.FeatureHit
import org.maplibre.compose.interaction.FeatureInteractionRow
import org.maplibre.compose.interaction.FeatureInteractions
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.FeatureQuery
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
    desiredRevision.value.value?.layers?.any { it.interactions.handler(family) != null } == true ||
      interactions.value.callbacks.features.any { it.interactions.handler(family) != null } ||
      interactions.value.callbacks.unhandled(family) != null

  fun capture(family: TapFamily): ClickPath? {
    val attachment = state.currentMapAttachment ?: return null
    val style = loadedStyle.value
    // Map rows come first so that, among rows with the same topmost layer, they run before the
    // layer's own row.
    val rows =
      mapRows().mapNotNull { (key, row) ->
        if (row.interactions.handler(family) == null) null
        else Row(row.layerIds, row.interactions.hitPadding) { mapRows()[key]?.interactions }
      } +
        desiredRevision.value.value?.layers.orEmpty().mapNotNull { node ->
          if (node.interactions.handler(family) == null) null
          else
            Row(setOf(node.definition.id), node.interactions.hitPadding) {
              desiredRevision.value.value
                ?.layers
                ?.firstOrNull {
                  it.definition.id == node.definition.id && it.registration === node.registration
                }
                ?.interactions
            }
        }

    // The same click must not query a replacement map or style.
    fun valid(): Boolean =
      !state.isClosed &&
        attachment.isValid &&
        state.currentMapAttachment === attachment &&
        loadedStyle.value === style &&
        (style == null || style.isLoaded)

    return ClickPath(::valid) { event ->
      if (!valid()) return@ClickPath ClickResult.Consume
      // The published handles carry the engine's layer order, including base-style layers, so a tap
      // makes no engine call. They appear one owner read after the style loads; a tap in that
      // window waits for them rather than reading the unpublished set as an empty layer stack. A
      // presentation that ends first drops the tap.
      if (rows.isNotEmpty()) {
        state.attachmentAuthority.awaitStyle(attachment)
        if (!valid()) return@ClickPath ClickResult.Consume
      }
      val frontFirst =
        if (rows.isNotEmpty() && style?.isLoaded == true)
          state.style.layerHandles().keys.toList().asReversed()
        else emptyList()
      val stackIndex = frontFirst.withIndex().associate { (index, id) -> id to index }
      // Each row runs at the position of its topmost layer; the stable sort keeps declaration
      // order for ties.
      val targets =
        rows
          .mapNotNull { row ->
            val ids = row.layerIds.filter { it in stackIndex }.sortedBy { stackIndex[it] }
            if (ids.isEmpty() || row.current()?.handler(family) == null) null else row to ids
          }
          .sortedBy { (_, ids) -> stackIndex.getValue(ids.first()) }
      if (targets.isEmpty()) return@ClickPath unhandled(family, event)

      // Only rows with a handler for this click are queried, all in one engine visit. Repeated rows
      // with growing padding query the same layer more than once.
      val hits =
        attachment.queryRenderedFeaturesByLayer(
          event.screenOffset,
          targets.flatMapTo(mutableSetOf()) { (row, ids) ->
            ids.map { FeatureQuery(it, row.hitPadding) }
          },
        )

      for ((row, ids) in targets) {
        // A query suspends, and a handler can change the map: check again before entering app code.
        if (!valid()) return@ClickPath ClickResult.Consume
        val handler = row.current()?.handler(family) ?: continue
        val rowHits = ids.flatMap { id ->
          hits[FeatureQuery(id, row.hitPadding)].orEmpty().map { FeatureHit(id, it) }
        }
        if (rowHits.isEmpty()) continue
        if (handler.onClick(FeatureClickEvent(event, rowHits)).consumed) {
          return@ClickPath ClickResult.Consume
        }
      }
      if (!valid()) return@ClickPath ClickResult.Consume

      unhandled(family, event)
    }
  }

  private fun unhandled(family: TapFamily, event: ClickEvent): ClickResult =
    interactions.value.callbacks.unhandled(family)?.invoke(event) ?: ClickResult.Pass

  // A row's layers and padding identify it across callback updates. An occurrence number tells
  // repeated rows apart without letting an unrelated insertion change their identity.
  private fun mapRows(): Map<RowKey, FeatureInteractionRow> {
    val occurrences = mutableMapOf<Pair<Set<String>, Dp>, Int>()
    return interactions.value.callbacks.features.associateBy { row ->
      val query = row.layerIds to row.interactions.hitPadding
      val occurrence = occurrences.getOrElse(query) { 0 }
      occurrences[query] = occurrence + 1
      RowKey(row.layerIds, row.interactions.hitPadding, occurrence)
    }
  }

  private data class RowKey(val layerIds: Set<String>, val hitPadding: Dp, val occurrence: Int)

  /** A row captured with a click; [current] reads its latest handlers, or null once removed. */
  private class Row(
    val layerIds: Set<String>,
    val hitPadding: Dp,
    val current: () -> FeatureInteractions?,
  )
}

private fun FeatureInteractions.handler(family: TapFamily): FeatureClickHandler? =
  when (family) {
    TapFamily.Tap -> onClick
    TapFamily.DoubleTap -> onDoubleClick
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> onLongClick
    TapFamily.TwoFingerTap -> null
  }

private fun InteractionCallbacks.unhandled(family: TapFamily): ((ClickEvent) -> ClickResult)? =
  when (family) {
    TapFamily.Tap -> unhandledClick
    TapFamily.DoubleTap -> unhandledDoubleClick
    TapFamily.SecondaryClick,
    TapFamily.LongPress -> unhandledLongClick
    TapFamily.TwoFingerTap -> null
  }
