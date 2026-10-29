package org.maplibre.compose.gljs

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsNumber

internal external interface CustomLayerInterface : LayerSpecification {
  override var id: String
  var type: String
  var renderingMode: String
  var onAdd: (MaplibreMap, JsAny) -> Unit
  var onRemove: (MaplibreMap, JsAny) -> Unit
  var render: (JsAny, CustomRenderMethodInput) -> Unit
}

internal external interface CustomRenderMethodInput : JsAny {
  val shaderData: CustomShaderData
  val defaultProjectionData: CustomProjectionData

  fun getProjectionData(options: CustomProjectionOptions): CustomProjectionData
}

internal external interface CustomShaderData : JsAny {
  val variantName: String
  val vertexShaderPrelude: String
  val define: String
}

internal external interface CustomProjectionData : JsAny {
  val mainMatrix: JsAny
  val fallbackMatrix: JsAny
  val tileMercatorCoords: JsArray<JsNumber>
  val clippingPlane: JsArray<JsNumber>
  val projectionTransition: Double
}

internal external interface GlJsStyleImage : JsAny {
  val data: StyleImageData
  val pixelRatio: Double
  val version: Int?
}

internal external interface CustomProjectionOptions : JsAny {
  var tileID: CustomTileId
  var applyGlobeMatrix: Boolean
}

internal external interface CustomTileId : JsAny {
  var wrap: Int
  var canonical: CustomCanonicalTileId
}

internal external interface CustomCanonicalTileId : JsAny {
  var x: Int
  var y: Int
  var z: Int
}
