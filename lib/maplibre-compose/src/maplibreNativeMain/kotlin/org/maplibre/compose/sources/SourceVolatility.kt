package org.maplibre.compose.sources

import org.maplibre.compose.style.MlnFfiStyleBinding

/**
 * Reads whether subsequent tile requests omit persistent storage writes for this source.
 *
 * Changing this value does not clear tiles already cached or reload tiles already in memory.
 * Sources that do not fetch tiles retain the value as metadata only. This query is available on
 * native platforms; MapLibre GL JS does not expose this storage policy.
 *
 * Like other handle operations, access fails after the source is removed or its style is replaced.
 */
public suspend fun SourceHandle.isVolatile(): Boolean = implementation.suspendingOperation {
  checkNotNull(
    (implementation.style as MlnFfiStyleBinding).awaitMap {
      it.styleSourceInfo(id)?.volatileSource
    }
  )
}

/**
 * Submits a change to the source's native storage policy and returns without waiting for native
 * work. A rejected write is logged. See [SourceHandle.isVolatile].
 */
public fun MutableSourceHandle.setVolatile(value: Boolean) {
  implementation.definitionOperation {
    (implementation.style as MlnFfiStyleBinding).setSourceVolatile(id, value)
  }
}
