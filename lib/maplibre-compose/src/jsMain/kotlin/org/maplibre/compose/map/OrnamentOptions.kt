package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * The browser platform draws no ornaments, so there is nothing here to configure.
 *
 * MapLibre GL JS's logo, attribution, compass, and scale bar are DOM controls that live in the
 * map's container. This platform composites the map into the Compose scene as a texture, so that
 * container is never on screen and anything mounted in it would be invisible — the same reason the
 * desktop platform ignores these options. Draw ornaments in Compose instead;
 * `maplibre-compose-material3` has a set, and attribution text is available from
 * [Source.attributionHtml][org.maplibre.compose.sources.Source.attributionHtml].
 */
@Immutable
public actual class OrnamentOptions {
  override fun equals(other: Any?): Boolean = other is OrnamentOptions

  override fun hashCode(): Int = 0

  override fun toString(): String = "OrnamentOptions(the browser platform draws no ornaments)"

  public actual companion object Companion {
    public actual val AllEnabled: OrnamentOptions = OrnamentOptions()
    public actual val OnlyLogo: OrnamentOptions = AllEnabled
    public actual val AllDisabled: OrnamentOptions = AllEnabled
  }
}
