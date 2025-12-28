@file:JsModule("maplibre-gl")

package org.maplibre.kmp.js.stylespec.sources

/** [ImageSourceSpecification](https://maplibre.org/maplibre-style-spec/sources/#image) */
public external interface ImageSourceSpecification : SourceSpecification {

  /** URL that points to an image. */
  public var url: String

  /**
   * Corners of image specified in longitude, latitude pairs. Corners order:
   * [topLeft, topRight, bottomRight, bottomLeft] Each corner is [longitude, latitude]
   */
  public var coordinates: Array<Array<Double>>
}
