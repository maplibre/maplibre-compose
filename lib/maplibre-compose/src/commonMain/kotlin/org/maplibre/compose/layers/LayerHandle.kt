package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.StyleMutationException

/**
 * Imperative property access to one layer in one loaded base-style generation.
 *
 * A mutation that MapLibre refuses throws [StyleHandleException].
 */
public class LayerHandle
internal constructor(
  public val id: String,
  public val type: String,
  private val style: StyleBinding,
  private val operations: StyleHandleOperationGuard,
) {
  private val identity: StyleIdentity = style.identity

  /** Returns the current value of [name], or null when the layer has no value for that property. */
  public fun getProperty(name: String): JsonElement? {
    return operation { style.layerProperty(id, name) }
  }

  /** Sets one layout [property][name] for this loaded style. */
  public fun setLayoutProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.LAYOUT)
  }

  /** Sets one paint [property][name] for this loaded style. */
  public fun setPaintProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.PAINT)
  }

  /** Sets one top-level [property][name], such as `minzoom`, for this loaded style. */
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
  operations: StyleHandleOperationGuard,
): LayerHandle? {
  requireCurrent()
  val layer = getLayer(id) ?: return null
  return LayerHandle(id, layer.definition().type, this, operations)
}
