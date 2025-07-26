package org.maplibre.compose.layers

internal actual sealed class Layer {
  abstract val impl: Nothing

  actual val id: String
    get() = TODO()

  actual var minZoom: Float
    get() = TODO()
    set(value) {
      TODO()
    }

  actual var maxZoom: Float
    get() = TODO()
    set(value) {
      TODO()
    }

  actual var visible: Boolean
    get() = TODO()
    set(value) {
      TODO()
    }

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
