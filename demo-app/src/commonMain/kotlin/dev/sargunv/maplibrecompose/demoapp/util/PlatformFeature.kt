package dev.sargunv.maplibrecompose.demoapp.util

enum class PlatformFeature {
  InteropBlending,
  LayerStyling;

  companion object {
    val Everything = entries.toSet()
  }
}
