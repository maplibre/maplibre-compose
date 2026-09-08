package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.style.CLEARED_TRANSITION
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.LayerPropertyWrite
import org.maplibre.compose.style.LayerSummary
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleException
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.StyleIdentity
import org.maplibre.compose.style.TRANSITION_SUFFIX
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.scaledBy
import org.maplibre.compose.style.toTransitionJson
import org.maplibre.compose.style.toTransitionOptions

internal class LayerHandleImpl
internal constructor(
  override val id: String,
  override val type: String,
  override val source: String?,
  override val sourceLayer: String?,
  private val style: StyleBinding,
  private val isCurrentResource: () -> Boolean,
  private val operations: StyleHandleOperationGuard,
) : LayerHandle {
  private val identity: StyleIdentity = style.identity

  override suspend fun getProperty(name: String): JsonElement? {
    return suspendingOperation { style.layerProperty(id, name) }
  }

  internal fun setLayoutProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.LAYOUT)
  }

  internal fun setPaintProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.PAINT)
  }

  internal fun setPaintTransition(property: String, options: TransitionOptions?) {
    setPaintProperty(
      property + TRANSITION_SUFFIX,
      options?.scaledBy(style.animatorDurationScale)?.toTransitionJson() ?: CLEARED_TRANSITION,
    )
  }

  override suspend fun getPaintTransition(property: String): TransitionOptions? =
    getProperty(property + TRANSITION_SUFFIX)?.toTransitionOptions()

  internal fun setRootProperty(name: String, value: JsonElement) {
    if (name in FIXED_ROOT_PROPERTIES) {
      throw StyleHandleException("'$name' is fixed for the generation of $type layer '$id'")
    }
    setProperty(name, value, LayerPropertyKind.ROOT)
  }

  internal fun clearFilter() {
    setProperty("filter", JsonNull, LayerPropertyKind.ROOT)
  }

  private fun setProperty(name: String, value: JsonElement, kind: LayerPropertyKind) {
    operation {
      operations.requireLayerWritable(id)
      style.setLayerProperties(listOf(LayerPropertyWrite(id, type, name, value, kind)))
    }
  }

  override val asMutable: MutableLayerHandle?
    get() = operation {
      if (operations.isLayerWritable(id)) MutableLayerHandleImpl(this) else null
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

  private companion object {
    val FIXED_ROOT_PROPERTIES = setOf("source", "source-layer")
  }
}

internal fun StyleBinding.layerHandle(
  id: String,
  summary: LayerSummary,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): LayerHandle {
  requireCurrent()
  return LayerHandleImpl(
    id = id,
    type = summary.type,
    source = summary.source,
    sourceLayer = summary.sourceLayer,
    style = this,
    isCurrentResource = isCurrentResource,
    operations = operations,
  )
}

private class MutableLayerHandleImpl(private val layer: LayerHandleImpl) :
  MutableLayerHandle, LayerHandle by layer {
  override fun setLayoutProperty(name: String, value: JsonElement) =
    layer.setLayoutProperty(name, value)

  override fun setPaintProperty(name: String, value: JsonElement) =
    layer.setPaintProperty(name, value)

  override fun setRootProperty(name: String, value: JsonElement) =
    layer.setRootProperty(name, value)

  override fun setPaintTransition(property: String, options: TransitionOptions?) =
    layer.setPaintTransition(property, options)

  override fun clearFilter() = layer.clearFilter()
}
