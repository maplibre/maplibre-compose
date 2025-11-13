@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.map

import org.maplibre.kmp.js.stylespec.Expression

/**
 * [QueryRenderedFeaturesOptions](https://maplibre.org/maplibre-gl-js/docs/API/type-aliases/QueryRenderedFeaturesOptions/)
 */
public sealed external interface QueryRenderedFeaturesOptions {
  public var availableImages: Array<String>?
  public var layers: Array<String>?
  public var filter: Expression?
  public var validate: Boolean?
}
