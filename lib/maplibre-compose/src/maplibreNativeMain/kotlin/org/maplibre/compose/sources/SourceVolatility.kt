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
public val SourceHandle.isVolatile: Boolean
  get() = implementation.operation {
    checkNotNull(
      (implementation.style as MlnFfiStyleBinding).readMap {
        it.styleSourceInfo(id)?.volatileSource
      }
    )
  }

/** Changes the source's native storage policy. See [SourceHandle.isVolatile]. */
public var MutableSourceHandle.isVolatile: Boolean
  get() = (this as SourceHandle).isVolatile
  set(value) {
    implementation.definitionOperation {
      (implementation.style as MlnFfiStyleBinding).mutateMap {
        it.setStyleSourceVolatile(id, value)
      }
    }
  }
