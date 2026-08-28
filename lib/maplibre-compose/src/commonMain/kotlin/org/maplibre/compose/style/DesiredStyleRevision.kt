package org.maplibre.compose.style

import org.maplibre.compose.sources.Source

/**
 * An immutable snapshot of the style composition's desired sources and layers. [MapState] commits
 * this revision, then [StyleApplier] diffs it against the binding.
 */
internal data class DesiredStyleRevision(
  val sources: Set<Source>,
  val layers: List<LayerNode<*>>,
) {
  val layerIds: Set<String>
    get() = layers.mapTo(hashSetOf()) { it.layer.id }

  val sourcesById: Map<String, Source>
    get() = sources.associateBy { it.id }
}
