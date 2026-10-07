package org.maplibre.compose.layers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.maplibre.compose.style.ClearedTransition
import org.maplibre.compose.style.LayerPropertyKind
import org.maplibre.compose.style.LayerPropertyWrite
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.style.StyleHandleOperationGuard
import org.maplibre.compose.style.TransitionOptions
import org.maplibre.compose.style.TransitionSuffix
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
  override suspend fun getProperty(name: String): JsonElement? = read {
    operations.visit { style.layerProperty(id, name) }
  }

  internal fun setLayoutProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.Layout)
  }

  internal fun setPaintProperty(name: String, value: JsonElement) {
    setProperty(name, value, LayerPropertyKind.Paint)
  }

  internal fun setPaintTransition(property: String, options: TransitionOptions?) {
    setPaintProperty(
      property + TransitionSuffix,
      options?.scaledBy(style.animatorDurationScale)?.toTransitionJson() ?: ClearedTransition,
    )
  }

  override suspend fun getPaintTransition(property: String): TransitionOptions? =
    getProperty(property + TransitionSuffix)?.toTransitionOptions()

  internal fun setRootProperty(name: String, value: JsonElement) {
    require(name !in FixedRootProperties) {
      "'$name' is fixed for the generation of $type layer '$id'"
    }
    setProperty(name, value, LayerPropertyKind.Root)
  }

  internal fun clearFilter() {
    setProperty("filter", JsonNull, LayerPropertyKind.Root)
  }

  private fun setProperty(name: String, value: JsonElement, kind: LayerPropertyKind) {
    write(name) {
      operations.requireLayerWritable(id)
      val writes = listOf(LayerPropertyWrite(id, type, name, value, kind))
      operations.post("$type layer '$id'") {
        if (isCurrentResource()) style.setLayerProperties(writes)
      }
    }
  }

  override val asMutable: MutableLayerHandle?
    get() {
      operations.requireOpen()
      return if (isLive() && operations.isLayerWritable(id)) MutableLayerHandleImpl(this) else null
    }

  /** False once the loaded style reloads or this layer is removed or replaced. */
  private fun isLive(): Boolean = operations.isReady() && style.isLoaded && isCurrentResource()

  /** Runs [action] while this handle is live; an expired handle logs and does nothing. */
  private inline fun write(name: String, action: () -> Unit) {
    operations.requireOpen()
    if (isLive()) action()
    else style.logger?.w { "$type layer '$id' ignored a write to '$name': the handle has expired" }
  }

  /** Returns null when this handle has expired, including during [action]. */
  private suspend fun <T> read(action: suspend () -> T?): T? {
    operations.requireOpen()
    if (!isLive()) return null
    val result = action()
    // A close during the read is a style change, not a use after close.
    return result.takeIf { isLive() }
  }

  private companion object {
    val FixedRootProperties = setOf("source", "source-layer")
  }
}

internal fun StyleBinding.layerHandle(
  summary: LayerSummary,
  isCurrentResource: () -> Boolean,
  operations: StyleHandleOperationGuard,
): LayerHandle {
  requireCurrent()
  return LayerHandleImpl(
    id = summary.id,
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
