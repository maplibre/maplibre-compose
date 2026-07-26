package org.maplibre.compose.style

import co.touchlab.kermit.Logger
import org.maplibre.nativeffi.map.MapHandle
import org.maplibre.nativeffi.render.RenderSessionHandle

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

  /** The session's logger, so a descriptor can report something it cannot otherwise surface. */
  val logger: Logger?

  /**
   * Runs [action] against the map on its owner thread.
   *
   * Returns null if the style has unloaded. Mutations should report that to the caller; reads
   * should fall back to the descriptor.
   */
  fun <T> withMap(action: (MapHandle) -> T): T?

  /**
   * Runs [action] against the render session on the owner thread, if there is one.
   *
   * Separate from [withMap] because a few things — feature extensions, and the supercluster queries
   * built on them — live on the render session rather than the map: they answer from what a render
   * pass actually built, so MapLibre exposes them nowhere else.
   *
   * Returns null when the style has unloaded or no render session is attached yet — both routine,
   * since a session exists only between the first frame and teardown, and is closed and reattached
   * on every resize. Failures are not caught: past those two cases MapLibre only throws for a wrong
   * thread, a handle used after close, or bad input, which are bugs rather than conditions.
   *
   * The handle must not escape [action]; it does not outlive the next resize.
   */
  fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T?

  companion object {
    /** A binding for a descriptor that has never been added to a style. */
    val UNLOADED: StyleBinding =
      object : StyleBinding {
        override val isLoaded: Boolean = false

        override val logger: Logger? = null

        override fun <T> withMap(action: (MapHandle) -> T): T? = null

        override fun <T> withRenderSession(action: (RenderSessionHandle) -> T): T? = null
      }
  }
}
