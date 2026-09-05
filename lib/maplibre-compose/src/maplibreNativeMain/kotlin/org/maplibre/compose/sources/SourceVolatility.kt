package org.maplibre.compose.sources

import org.maplibre.compose.style.MlnFfiStyleBinding

/**
 * Whether subsequent tile requests omit persistent storage writes for this source.
 *
 * Changing this value does not clear tiles already cached or reload tiles already in memory.
 * Sources that do not fetch tiles retain the value as metadata only. This property is available on
 * native platforms; MapLibre GL JS does not expose this storage policy.
 *
 * Like other handle operations, access fails after the source is removed or its style is replaced.
 */
public var SourceHandle.isVolatile: Boolean
  get() = operation {
    checkNotNull((style as MlnFfiStyleBinding).readMap { it.styleSourceInfo(id)?.volatileSource })
  }
  set(value) {
    operation { (style as MlnFfiStyleBinding).mutateMap { it.setStyleSourceVolatile(id, value) } }
  }
