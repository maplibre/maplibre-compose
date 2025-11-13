package org.maplibre.kmp.js.stylespec

import org.maplibre.kmp.js.util.jso

public fun TerrainSpecification(
  source: String,
  exaggeration: Double? = null,
): TerrainSpecification = jso {
  this.source = source
  exaggeration?.let { this.exaggeration = it }
}

public typealias Expression = Array<*>
