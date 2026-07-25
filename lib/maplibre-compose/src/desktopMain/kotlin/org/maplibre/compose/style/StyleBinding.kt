package org.maplibre.compose.style

import org.maplibre.nativeffi.map.MapHandle

/**
 * A source or layer's connection to the live style it belongs to.
 *
 * Desktop sources and layers are live descriptors: before they are added to a style they hold their
 * own state, and once added every mutation goes straight to MapLibre. This is the seam between the
 * two. It also carries the owner-thread hop, because every `MapHandle` call has to run there.
 *
 * A binding stops working when its style unloads, which happens on every style change. Callers get
 * [UNLOADED] rather than an exception for reads, so a composable that outlives a style swap by a
 * frame degrades instead of crashing.
 */
internal interface StyleBinding {
  /** Whether the style this binding belongs to is still loaded. */
  val isLoaded: Boolean

  /**
   * Runs [action] against the map on its owner thread.
   *
   * Returns null if the style has unloaded. Mutations should report that to the caller; reads
   * should fall back to the descriptor.
   */
  fun <T> withMap(action: (MapHandle) -> T): T?

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override fun <T> withMap(action: (MapHandle) -> T): T? = null
      }
  }
}
