package org.maplibre.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry

/** A rendered feature and the style layer through which it was hit. */
@Immutable
public data class FeatureHit(
  public val layerId: String,
  public val feature: Feature<Geometry, JsonObject?>,
)

/**
 * Runs with the click as receiver. Hits are ordered by style layer, front first, preserving
 * [org.maplibre.compose.map.MapState.queryRenderedFeatures] ordering within each layer. The same
 * feature can be hit through several layers. Geometries follow that query's coordinate contract.
 */
public typealias FeatureClickHandler = ClickEvent.(hits: List<FeatureHit>) -> ClickResult

/**
 * Handlers and query padding shared by one feature row. A layer's `interactions` creates a row for
 * that layer. Map rows are declared with [FeatureRowsBuilder.on].
 *
 * A handler runs only when its row has hits. [ClickResult.Pass] continues to the next row;
 * [ClickResult.Consume] stops delivery and the camera response. Touch clicks wait for double-tap
 * disambiguation when a double tap can also respond.
 *
 * Each block describes the complete row; omitted handlers are absent. Use ordinary receiver
 * extensions to share configuration between layers and map rows.
 */
@MapInteractionDsl
public class FeatureInteractionsBuilder internal constructor() {
  /**
   * Query radius in map-local dp. Zero queries a point; a positive value queries a square centered
   * on the click. Must be finite and nonnegative.
   */
  public var hitPadding: Dp = 0.dp
  private var onClick: FeatureClickHandler? = null
  private var onDoubleClick: FeatureClickHandler? = null
  private var onLongClick: FeatureClickHandler? = null

  public fun click(block: FeatureClickHandler?) {
    onClick = block
  }

  public fun doubleClick(block: FeatureClickHandler?) {
    onDoubleClick = block
  }

  /** Respond to a touch long press or secondary mouse click. */
  public fun longClick(block: FeatureClickHandler?) {
    onLongClick = block
  }

  internal fun build(): FeatureInteractions {
    require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
      "hitPadding must be finite and nonnegative"
    }
    return FeatureInteractions(hitPadding, onClick, onDoubleClick, onLongClick)
  }
}

/**
 * Declares feature rows for composed or base-style layers. Rows run front to back by their topmost
 * existing layer, even when only a lower layer in the row has hits. Ties run in declaration order,
 * followed by the layer's own row. Missing layers are skipped.
 *
 * Callbacks update without restarting input. Pending clicks use the latest handlers for rows with
 * the same layer IDs and padding; repeated rows with those settings are matched in declaration
 * order. Removed rows are skipped, and replacing the map or style drops pending delivery.
 */
@MapInteractionDsl
public class FeatureRowsBuilder internal constructor() {
  internal val rows = mutableListOf<FeatureInteractionRow>()

  /** Adds one row pooling hits from [layerIds], using the same builder as a layer shorthand. */
  public fun on(vararg layerIds: String, block: FeatureInteractionsBuilder.() -> Unit) {
    require(layerIds.isNotEmpty() && layerIds.all { it.isNotBlank() }) {
      "Feature rows must name at least one nonblank layer ID"
    }
    rows +=
      FeatureInteractionRow(layerIds.toSet(), FeatureInteractionsBuilder().apply(block).build())
  }
}

internal data class FeatureInteractions(
  val hitPadding: Dp = 0.dp,
  val onClick: FeatureClickHandler? = null,
  val onDoubleClick: FeatureClickHandler? = null,
  val onLongClick: FeatureClickHandler? = null,
)

internal data class FeatureInteractionRow(
  val layerIds: Set<String>,
  val interactions: FeatureInteractions,
)

/** Keeps callback identities through recomposition while observing state read by the builder. */
@Composable
internal fun rememberFeatureInteractions(
  block: FeatureInteractionsBuilder.() -> Unit
): FeatureInteractions =
  remember(block) { derivedStateOf { FeatureInteractionsBuilder().apply(block).build() } }.value
