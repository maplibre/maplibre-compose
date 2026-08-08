package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

/**
 * A source or layer's connection to the live style it belongs to. Carries the owner-thread hop,
 * because every `MapHandle` call has to run there.
 *
 * A binding stops working when its style unloads, which happens on every style change; reads then
 * fall back to [UNLOADED] rather than throwing.
 */
internal interface StyleBinding {
  val isLoaded: Boolean

  val logger: Logger?

  /**
   * Runs [action] against the map on its owner thread. Returns null if the style has unloaded;
   * reads should then fall back to the descriptor.
   */
  fun <T> readMap(action: (MapHandle) -> T): T?

  /** Runs a mutation and requests a repaint after native accepts it. */
  fun <T> mutateMap(action: (MapHandle) -> T): T?

  /**
   * Runs [action] against the render session on its renderer thread, if there is one. Separate from
   * map access because feature extensions and the supercluster queries built on them are
   * renderer-scoped in mbgl and exposed nowhere else.
   *
   * Returns null when the style has unloaded or no render session is attached yet — a session
   * exists only between the first frame and teardown, and is reattached on every resize. The handle
   * must not escape [action].
   */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun <T> readMap(action: (MapHandle) -> T): T? = null

        override fun <T> mutateMap(action: (MapHandle) -> T): T? = null

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}
