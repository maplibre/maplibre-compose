package org.maplibre.compose.demoapp.demos.featureediting

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import org.maplibre.compose.editing.EditorFeature
import org.maplibre.spatialk.geojson.FeatureId

/** A feature kept in a list until its exit animation has finished. */
internal class LaggingEntry(val id: FeatureId, feature: EditorFeature) {
  /** The current feature, or the last known copy while the entry exits. */
  var feature by mutableStateOf(feature)

  val visible = MutableTransitionState(false).apply { targetState = true }
}

/**
 * Mirrors [features] with one entry per id, in the same order. An entry is added with its
 * transition targeting visible, keeps the last copy of a removed feature in place while its
 * transition runs out, and is dropped once that transition ends, so every removal path animates
 * out.
 */
@Composable
internal fun rememberLaggingEntries(features: List<EditorFeature>): List<LaggingEntry> {
  val entries = remember { mutableStateListOf<LaggingEntry>() }
  LaunchedEffect(features) {
    val order = HashMap<FeatureId, Int>(features.size)
    features.forEachIndexed { index, feature -> feature.id?.let { order[it] = index } }
    val byId = entries.associateBy { it.id }
    val merged = ArrayList<Pair<Double, LaggingEntry>>(entries.size + features.size)
    var previous = -1.0
    for (entry in entries) {
      val index = order[entry.id]
      if (index != null) {
        entry.feature = features[index]
        entry.visible.targetState = true
        previous = index.toDouble()
        merged += index.toDouble() to entry
      } else {
        entry.visible.targetState = false
        merged += previous + 0.5 to entry
      }
    }
    for (feature in features) {
      val id = feature.id ?: continue
      if (id !in byId) merged += checkNotNull(order[id]).toDouble() to LaggingEntry(id, feature)
    }
    merged.sortBy { it.first }
    val sorted = merged.map { it.second }
    if (sorted != entries.toList()) {
      entries.clear()
      entries.addAll(sorted)
    }
  }
  LaunchedEffect(entries) {
    snapshotFlow { entries.filter { it.visible.isIdle && !it.visible.currentState } }
      .collect { finished -> if (finished.isNotEmpty()) entries.removeAll(finished) }
  }
  return entries
}
