package org.maplibre.compose.gljs

internal external interface CustomLayerInterface : LayerSpecification {
  override var id: String
  var type: String
  var renderingMode: String
  var onAdd: (MaplibreMap, dynamic) -> Unit
  var onRemove: (MaplibreMap, dynamic) -> Unit
  var render: (dynamic, CustomRenderMethodInput) -> Unit
}

internal external interface CustomRenderMethodInput {
  val shaderData: CustomShaderData
  val defaultProjectionData: CustomProjectionData

  fun getProjectionData(options: CustomProjectionOptions): CustomProjectionData
}

internal external interface CustomShaderData {
  val variantName: String
  val vertexShaderPrelude: String
  val define: String
}

internal external interface CustomProjectionData {
  val mainMatrix: dynamic
  val fallbackMatrix: dynamic
  val tileMercatorCoords: Array<Double>
  val clippingPlane: Array<Double>
  val projectionTransition: Double
}

internal external interface GlJsStyleImage {
  val data: StyleImageData
  val pixelRatio: Double
  val version: Int?
}

internal external interface CustomProjectionOptions {
  var tileID: CustomTileId
  var applyGlobeMatrix: Boolean
}

internal external interface CustomTileId {
  var wrap: Int
  var canonical: CustomCanonicalTileId
}

internal external interface CustomCanonicalTileId {
  var x: Int
  var y: Int
  var z: Int
}
