package org.maplibre.compose.interaction.internal

import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
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
    val rows =
      mapRows().mapNotNull { (key, row) ->
        row.interactions
          .takeIf { it.handler(family) != null }
          ?.let {
            Target(row.layerIds, it) { mapRows()[key]?.interactions }
          }
      } +
        desiredRevision.value.value?.layers.orEmpty().mapNotNull { node ->
          node.interactions
            .takeIf { it.handler(family) != null }
            ?.let {
              Target(setOf(node.definition.id), it) {
                desiredRevision.value.value
                  ?.layers
                  ?.firstOrNull {
                    it.definition.id == node.definition.id && it.registration === node.registration
                  }
                  ?.interactions
              }
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
      // Published handles include base-style layers. Waiting here also covers maps with only map
      // rows, while keeping all owner-thread engine reads out of the synchronous capture path.
      if (rows.isNotEmpty()) {
        attachment.runLeaseBound { state.style.awaitLoaded() }
        if (!valid()) return@ClickPath ClickResult.Consume
      }
      val layerIds =
        if (rows.isNotEmpty() && style?.isLoaded == true)
          state.style.layerHandles().keys.toList().asReversed()
        else emptyList()
      val targets =
        rows
          .mapNotNull { row ->
            val ids = layerIds.filter { it in row.layerIds }
            if (ids.isEmpty() || row.current()?.handler(family) == null) null else row to ids
          }
          .sortedBy { (_, ids) -> layerIds.indexOf(ids.first()) }
      if (targets.isEmpty()) return@ClickPath unhandled(family, event)

      // A repeated layer can have several padding values. Share identical queries across rows and
      // run the entire batch in one Native renderer visit.
      val queries =
        targets
          .flatMap { (row, ids) ->
            ids.map { FeatureQuery(it, row.interactions.hitPadding) }
          }
          .toSet()
      val hits = attachment.queryRenderedFeaturesByLayer(event.screenOffset, queries)

      for ((row, ids) in targets) {
        // A query suspends, and a handler can change the map: check again before entering app code.
        if (!valid()) return@ClickPath ClickResult.Consume
        val handler = row.current()?.handler(family) ?: continue
        val features = ids.flatMap { id ->
          hits[FeatureQuery(id, row.interactions.hitPadding)].orEmpty().map { FeatureHit(id, it) }
        }
        if (features.isEmpty()) continue
        if (event.handler(features).consumed) return@ClickPath ClickResult.Consume
      }
      if (!valid()) return@ClickPath ClickResult.Consume
      unhandled(family, event)
    }
  }

  private fun unhandled(family: TapFamily, event: ClickEvent): ClickResult =
    interactions.value.callbacks.unhandled(family)?.invoke(event) ?: ClickResult.Pass

  // A row's targets and padding identify its query across callback updates. An occurrence number
  // distinguishes repeated rows without making unrelated insertions change their identity.
  private fun mapRows(): Map<RowKey, FeatureInteractionRow> {
    val occurrences = mutableMapOf<Pair<Set<String>, Dp>, Int>()
    return interactions.value.callbacks.features.associateBy { row ->
      val query = row.layerIds to row.interactions.hitPadding
      val occurrence = occurrences.getOrElse(query) { 0 }
      occurrences[query] = occurrence + 1
      RowKey(query.first, query.second, occurrence)
    }
  }

  private data class RowKey(val layerIds: Set<String>, val padding: Dp, val occurrence: Int)

  private class Target(
    val layerIds: Set<String>,
    val interactions: FeatureInteractions,
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
