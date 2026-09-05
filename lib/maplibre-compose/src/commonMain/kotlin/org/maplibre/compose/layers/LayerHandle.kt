package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.style.CLEARED_TRANSITION
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleMutationException
import org.maplibre.compose.style.TRANSITION_SUFFIX
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.toTransitionJson
import org.maplibre.compose.style.toTransitionOptions

/** Provides imperative property access to a layer for one loaded base-style generation. */
public class LayerHandle
internal constructor(
  public val id: String,
  public val type: String,
  private val style: StyleBinding,
  private val isCurrentResource: () -> Boolean,
  private val operations: StyleHandleOperationGuard,
) {
  private val identity: StyleIdentity = style.identity

  /** Returns the current value of [name], or null when the layer has no value for that property. */
  public fun getProperty(name: String): JsonElement? {
    return operation { style.layerProperty(id, name) }
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
  public fun getPaintTransition(property: String): TransitionOptions? =
    getProperty(property + TRANSITION_SUFFIX)?.toTransitionOptions()

  /** Sets the top-level property [name], such as `minzoom`, for this loaded style. */
  public fun setRootProperty(name: String, value: JsonElement) {
    operation {
      mutate(name) {
        if (name == "filter") style.setLayerFilter(id, value)
        else style.setLayerProperty(id, name, value, LayerPropertyKind.ROOT)
      }
    }
  }

  /** Removes the layer filter for this loaded style. */
  public fun clearFilter() {
    operation { mutate("filter") { style.setLayerFilter(id, JsonNull) } }
  }

  private fun setProperty(name: String, value: JsonElement, kind: LayerPropertyKind) {
    operation { mutate(name) { style.setLayerProperty(id, name, value, kind) } }
  }

  private fun requireCurrent() {
    style.requireCurrent(identity)
    check(isCurrentResource()) { "Layer '$id' is no longer owned by this handle" }
    val currentType = style.getLayer(id)?.definition()?.type
    check(currentType == type) { "Layer '$id' is no longer the $type layer owned by this handle" }
  }

  private fun <T> operation(action: () -> T): T = operations.run {
    requireCurrent()
    action()
  }

  private inline fun mutate(property: String, action: () -> Unit) {
    try {
      action()
    } catch (error: StyleMutationException) {
      throw StyleHandleException(
        "Could not set '$property' on $type layer '$id': ${error.message}",
        error,
      )
    }
  }
}

internal fun StyleBinding.layerHandle(
  id: String,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): LayerHandle? {
  requireCurrent()
  val layer = getLayer(id) ?: return null
  return LayerHandle(id, layer.definition().type, this, isCurrentResource, operations)
}
