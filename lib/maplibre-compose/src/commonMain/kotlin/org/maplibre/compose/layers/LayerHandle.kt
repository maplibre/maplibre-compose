package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.style.CLEARED_TRANSITION
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.LayerPropertyWrite
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.TRANSITION_SUFFIX
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.summary
import org.maplibre.compose.style.toTransitionJson
import org.maplibre.compose.style.toTransitionOptions

/**
 * Provides property access to a layer for one loaded base-style generation.
 *
 * A setter returns before the engine applies the write. A value the engine rejects is logged, and
 * the layer keeps its previous value.
 *
 * Style content owns all properties of declared layers. Their properties can be read, but setter
 * calls and [clearFilter] throw [StyleHandleException]. Base-style layers also permit writes,
 * except through the handles that an [Anchor] predicate receives, which are read-only.
 */
public class LayerHandle
internal constructor(
  public val id: String,
  public val type: String,
  /** The ID of the source the layer draws from, or null for a layer without one. */
  public val source: String?,
  /** The source layer the layer draws from, or null when the layer names none. */
  public val sourceLayer: String?,
  private val style: StyleBinding,
  private val isCurrentResource: () -> Boolean,
  private val operations: StyleHandleOperationGuard,
) {
  private val identity: StyleIdentity = style.identity

  /** Returns the current value of [name], or null when the layer has no value for that property. */
  public suspend fun getProperty(name: String): JsonElement? {
    return suspendingOperation { style.layerProperty(id, name) }
  }

  /** Sets the layout property [name] for this loaded style. */
  public fun setLayoutProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.LAYOUT)
  }

  /** Sets the paint property [name] for this loaded style. */
  public fun setPaintProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.PAINT)
  }

  /**
   * Sets the transition of the paint property [property], named without the `-transition` suffix. A
   * null [options] returns the property to the style's global transition. The reset is written at
   * once, as the spec's empty transition object; a layer composable drops the key from its layer
   * definition instead, with the same result.
   *
   * On Android, the system animator duration scale multiplies the timing that reaches the engine. A
   * scale of zero applies the property change instantly.
   */
  public fun setPaintTransition(property: String, options: TransitionOptions?) {
    setPaintProperty(
      property + TRANSITION_SUFFIX,
      options?.scaledBy(style.animatorDurationScale)?.toTransitionJson() ?: CLEARED_TRANSITION,
    )
  }

  /**
   * Returns the transition of the paint property [property], named without the `-transition`
   * suffix.
   *
   * Returns null when the layer states no transition for that property, and when it states one
   * without both a duration and a delay: an engine times the omitted field with the style's global
   * transition, which [TransitionOptions] states no value for. MapLibre GL JS reports an empty
   * object for a transition that was cleared, and this returns null for it.
   *
   * The reported timing is the engine's: a transition that the library wrote is under the animator
   * duration scale of the time it was written.
   */
  public suspend fun getPaintTransition(property: String): TransitionOptions? =
    getProperty(property + TRANSITION_SUFFIX)?.toTransitionOptions()

  /** Sets the top-level property [name], such as `minzoom`, for this loaded style. */
  public fun setRootProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.ROOT)
  }

  /** Removes the layer filter for this loaded style. */
  public fun clearFilter() {
    setProperty("filter", JsonNull, LayerPropertyKind.ROOT)
  }

  /**
   * Posts one write through the batched path, so the engine's rejection is logged the way a
   * rejected composed property is, and the caller does not wait for the engine.
   */
  private fun setProperty(name: String, value: JsonElement, kind: LayerPropertyKind) {
    operation {
      operations.requireLayerWritable(id)
      style.setLayerProperties(listOf(LayerPropertyWrite(id, type, name, value, kind)))
    }
  }

  private fun requireCurrent() {
    style.requireCurrent(identity)
    check(isCurrentResource()) { "Layer '$id' is no longer the $type layer owned by this handle" }
  }

  private fun <T> operation(action: () -> T): T = operations.run {
    requireCurrent()
    action()
  }

  private suspend fun <T> suspendingOperation(action: suspend () -> T): T {
    operation {}
    val result = action()
    operation {}
    return result
  }
}

/**
 * A handle for the style state, which outlives revisions. A layer replaced under the same ID takes
 * a new identity, so [isCurrentResource] refuses the handle of the replaced one.
 */
internal fun StyleBinding.layerHandle(
  id: String,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): LayerHandle? {
  requireCurrent()
  val summary = getLayer(id)?.definition()?.summary() ?: return null
  return LayerHandle(
    id = id,
    type = summary.type,
    source = summary.source,
    sourceLayer = summary.sourceLayer,
    style = this,
    isCurrentResource = isCurrentResource,
    operations = operations,
  )
}
