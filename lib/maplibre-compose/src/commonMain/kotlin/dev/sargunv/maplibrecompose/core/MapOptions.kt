package dev.sargunv.maplibrecompose.core

public data class MapOptions(
  val renderOptions: RenderOptions = RenderOptions.Standard,
  val gestureOptions: GestureOptions = GestureOptions.Standard,
  val ornamentOptions: OrnamentOptions = OrnamentOptions.Standard,
)
