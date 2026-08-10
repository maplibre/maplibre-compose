package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

/**
 * The browser platform draws no ornaments. Draw them in Compose instead; attribution text is
 * available from [Source.attributionHtml][org.maplibre.compose.sources.Source.attributionHtml].
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
