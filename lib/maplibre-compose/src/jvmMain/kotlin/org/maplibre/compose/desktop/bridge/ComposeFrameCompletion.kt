package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.DirectContext

/**
 * Holds the frames a host acquired while Compose reads them, and releases each once Compose has
 * finished reading it, before MapLibre writes its texture again.
 *
 * Skiko exposes no queue or semaphore for the session to wait on, so a release first waits on the
 * CPU for Compose's submitted GPU work and then reports the reads complete.
 */
internal class ComposeFrameCompletion {
  private class HeldFrame(val release: (Boolean) -> Unit) {
    var drawn = false
  }

  private var currentContext: DirectContext? = null
  private var preserveFrame: (() -> Unit)? = null

  /** Oldest first. Only the newest can be undrawn: a newer frame releases an undrawn one. */
  private val held = ArrayDeque<HeldFrame>()

  /**
   * Whether a frame is held, so that the texture of the newest one holds that frame and Compose can
   * read it directly. Otherwise Compose draws the image [prepare] preserved.
   */
  val hasFrame: Boolean
    get() = held.isNotEmpty()

  /**
   * Holds a newly acquired frame. A held frame Compose never drew has no reads to wait for, so the
   * new one releases it.
   */
  fun holdFrame(release: (Boolean) -> Unit) {
    val newest = held.lastOrNull()
    if (newest != null && !newest.drawn) {
      newest.release(true)
      held.removeLast()
    }
    held.addLast(HeldFrame(release))
  }

  /**
   * Makes [context] ready for MapLibre's next frame.
   *
   * With [keepNewest], as for a ring of textures, the newest frame stays held for Compose to draw,
   * and older ones are released once Compose has drawn the newest. Otherwise, as before MapLibre
   * writes a single texture again or resizes a ring, every frame is released, after Compose's
   * current image of it is preserved.
   */
  fun prepare(context: DirectContext, keepNewest: Boolean = false, contextReplaced: () -> Unit) {
    val previousContext = currentContext
    val pendingFrame = preserveFrame
    if (previousContext != null && previousContext !== context) {
      preserveFrame = null
      contextReplaced()
      // The old context's image is gone, so a ring keeps its newest frame for the new context to
      // read again; an idle map has no newer frame coming.
      releaseOldest(keep = if (keepNewest) 1 else 0)
    } else if (keepNewest) {
      if (held.size > 1 && held.last().drawn) {
        // Compose no longer draws the older frames, but its submitted work may still read them.
        context.flush()
        context.submit(syncCpu = true)
        releaseOldest(keep = 1)
      }
    } else {
      if (pendingFrame != null) {
        pendingFrame()
        context.flush()
        context.submit(syncCpu = true)
        preserveFrame = null
      }
      releaseOldest(keep = 0)
    }
    currentContext = context
  }

  /**
   * Calls [contextReplaced] when Compose draws with a context other than the last prepared one, so
   * the host drops wrappers built for the old context. Held frames stay acquired: Compose still
   * reads them, and the next [prepare] releases them.
   */
  fun adoptContext(context: DirectContext, contextReplaced: () -> Unit) {
    val previousContext = currentContext
    if (previousContext == null || previousContext === context) return
    preserveFrame = null
    contextReplaced()
    currentContext = context
  }

  /**
   * Records that Compose drew the newest held frame and reads it when it replays the current
   * picture. [preserve] keeps Compose's image of the frame after the frame is released.
   */
  fun frameRecorded(preserve: () -> Unit) {
    check(currentContext != null) { "The Compose context was not prepared" }
    held.lastOrNull()?.drawn = true
    preserveFrame = preserve
  }

  /** Forgets work tied to a context the host has replaced. */
  fun abandon() {
    preserveFrame = null
    currentContext = null
    while (held.isNotEmpty()) {
      held.first().release(false)
      held.removeFirst()
    }
  }

  /**
   * Releases held frames, oldest first, until [keep] remain. Each stays held until its release
   * returns, so a later call retries one that failed.
   */
  private fun releaseOldest(keep: Int) {
    while (held.size > keep) {
      held.first().release(true)
      held.removeFirst()
    }
  }
}
