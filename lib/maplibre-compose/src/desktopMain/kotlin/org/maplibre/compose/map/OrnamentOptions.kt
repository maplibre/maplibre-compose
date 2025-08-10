package org.maplibre.compose.map

import androidx.compose.runtime.Immutable

@Immutable
public actual class OrnamentOptions {
  public actual companion object {
    public actual val AllEnabled: OrnamentOptions = OrnamentOptions()
    public actual val AllDisabled: OrnamentOptions = OrnamentOptions()
    public actual val OnlyLogo: OrnamentOptions = OrnamentOptions()
  }

  override fun equals(other: Any?): Boolean = other is OrnamentOptions

  override fun hashCode(): Int = javaClass.hashCode()
}
