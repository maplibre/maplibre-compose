package org.maplibre.compose.demoapp.util

enum class PlatformFeature {
  InteropBlending,
  LayerStyling;

  companion object {
    val Everything = entries.toSet()
  }
}
