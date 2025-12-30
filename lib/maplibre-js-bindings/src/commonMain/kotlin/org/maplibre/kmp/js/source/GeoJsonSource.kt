package org.maplibre.kmp.js.source

import org.maplibre.kmp.js.stylespec.sources.GeoJsonDataDefinition

public abstract external class GeoJsonSource : Source {
  override var id: String
    get() = definedExternally
    set(value) = definedExternally

  override var attribution: String?
    get() = definedExternally
    set(value) = definedExternally

  public fun setData(data: GeoJsonDataDefinition)
}
