package org.maplibre.compose.style

/** Opaque identity for one loaded base-style generation. */
internal class StyleIdentity private constructor() {
  companion object {
    fun create(): StyleIdentity = StyleIdentity()
  }
}
