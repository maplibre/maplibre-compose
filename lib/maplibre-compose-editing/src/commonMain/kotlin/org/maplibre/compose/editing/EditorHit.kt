package org.maplibre.compose.editing

import androidx.compose.ui.unit.Dp
import org.maplibre.spatialk.geojson.FeatureId

/** What a pointer landed on. */
public sealed interface EditorHit

/** A handle under the pointer. */
public data class HandleHit(val handle: EditorHandle) : EditorHit

/**
 * A feature under the pointer. [segmentStart] is the path of the vertex that starts the nearest
 * segment, or the nearest position of a Point or MultiPoint; null for a GeometryCollection.
 * [distance] is the screen distance to that segment or position, approximate under pitch.
 */
public data class FeatureHit(
  val featureId: FeatureId,
  val segmentStart: VertexRef?,
  val distance: Dp,
) : EditorHit
