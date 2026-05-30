package org.maplibre.compose.layers

import org.maplibre.kmp.native.style.layers.Layer as MLNLayer

internal actual sealed class Layer {
  abstract val impl: MLNLayer

  actual val id: String
    get() = impl.id

  actual var minZoom: Float
    get() = impl.minZoom
    set(value) {
      impl.minZoom = value
    }

  actual var maxZoom: Float
    get() = impl.maxZoom
    set(value) {
      impl.maxZoom = value
    }

  actual var visible: Boolean
    get() = impl.visible
    set(value) {
      impl.visible = value
    }

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
