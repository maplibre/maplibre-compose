package org.maplibre.compose.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Position

/** A rendered feature and the ID of the style layer that drew it. */
@Immutable
public data class FeatureHit
internal constructor(
  public val layerId: String,
  /** Geometries follow [org.maplibre.compose.map.MapState.queryRenderedFeatures]. */
  public val feature: Feature<Geometry, JsonObject?>,
)

/**
 * A map click that hit features in a feature row, with the same click data as [ClickEvent].
 *
 * [hits] is never empty. It is ordered by style layer, front first, and within each layer in the
 * order of [org.maplibre.compose.map.MapState.queryRenderedFeatures]. The same feature can appear
 * once for each layer that drew it.
 */
@Immutable
public class FeatureClickEvent
internal constructor(
  click: ClickEvent,
  public val hits: List<FeatureHit>,
) {
  public val uptimeMillis: Long = click.uptimeMillis
  /** Map-local position in dp, before camera padding. */
  public val screenOffset: DpOffset = click.screenOffset
  /** Geographic position in the pointed-to world copy; longitude may fall outside ±180°. */
  public val position: Position? = click.position
  /** Android accessibility gestures may report [PointerType.Unknown]. */
  public val pointerTypes: Set<PointerType> = click.pointerTypes
  public val buttons: Set<PointerButton> = click.buttons
  public val modifierKeys: Set<KeyModifier> = click.modifierKeys
}

/**
 * Responds to a click that hit features in one feature row. Set it with
 * [FeatureInteractionsBuilder.onClick] and the related builder functions.
 */
public fun interface FeatureClickHandler {
  /**
   * Returns [ClickResult.Pass] to continue with the next feature row or [ClickResult.Consume] to
   * stop click handling, including the camera response.
   *
   * The map calls this on the main thread, for one click at a time. The map does not catch
   * exceptions it throws.
   */
  public fun onClick(event: FeatureClickEvent): ClickResult
}

/**
 * The handlers and hit padding of one feature row. A layer's `interactions` block describes the row
 * for that layer; [FeatureRowsBuilder.layers] describes a row for any layers in the style.
 *
 * A handler runs only when the row's query finds features. When a double tap can also respond, as
 * with the default double-tap zoom, a touch tap reaches [onClick] only after the double-tap
 * timeout, because the map waits for a possible second tap.
 *
 * Each block describes the complete row: handlers it does not set are absent.
 */
@MapInteractionDsl
public class FeatureInteractionsBuilder internal constructor() {
  /**
   * How far from the click the row finds features, in dp. Zero queries the clicked point; a
   * positive value queries a square with this half-width centered on the click. Must be finite and
   * nonnegative.
   */
  public var hitPadding: Dp = 0.dp
    set(value) {
      require(value.value.isFinite() && value.value >= 0f) {
        "hitPadding must be finite and nonnegative, was $value"
      }
      field = value
    }

  private var click: FeatureClickHandler? = null
  private var doubleClick: FeatureClickHandler? = null
  private var longClick: FeatureClickHandler? = null

  /** Responds to a click or tap. Null removes the handler. */
  public fun onClick(handler: FeatureClickHandler?) {
    click = handler
  }

  /** Responds to a double click or double tap. Null removes the handler. */
  public fun onDoubleClick(handler: FeatureClickHandler?) {
    doubleClick = handler
  }

  /** Responds to a touch long press or secondary mouse click. Null removes the handler. */
  public fun onLongClick(handler: FeatureClickHandler?) {
    longClick = handler
  }

  internal fun build(): FeatureInteractions =
    FeatureInteractions(hitPadding, click, doubleClick, longClick)
}

/**
 * Declares feature rows for composed or base-style layers.
 *
 * Rows run front to back by their topmost layer in the style, even when only a lower layer of the
 * row has hits. Rows with the same topmost layer run in declaration order, followed by that layer's
 * own `interactions` row. Layers missing from the style are skipped.
 *
 * Handlers update without restarting input. A click whose query is still running uses the latest
 * handlers of rows with the same layer IDs and padding; repeated rows with the same settings are
 * matched in declaration order. Rows removed before their turn are skipped.
 */
@MapInteractionDsl
public class FeatureRowsBuilder internal constructor() {
  internal val rows = mutableListOf<FeatureInteractionRow>()

  /**
   * Adds one row whose handlers receive hits from all of [layerIds] together. Repeating the same
   * layers with growing [FeatureInteractionsBuilder.hitPadding] searches outward from the click.
   */
  public fun layers(vararg layerIds: String, block: FeatureInteractionsBuilder.() -> Unit) {
    require(layerIds.isNotEmpty()) { "A feature row must name at least one layer ID" }
    require(layerIds.none { it.isBlank() }) {
      "Feature row layer IDs must not be blank, was ${layerIds.toList()}"
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

/** Runs [block] again when it changes or when state it reads changes. */
@Composable
internal fun rememberFeatureInteractions(
  block: FeatureInteractionsBuilder.() -> Unit,
): FeatureInteractions =
  remember(block) { derivedStateOf { FeatureInteractionsBuilder().apply(block).build() } }.value
