package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.DirectContext

/** Waits for Compose to finish reading the shared target before MapLibre writes it again. */
internal class ComposeFrameCompletion {
  private var currentContext: DirectContext? = null
  private var preserveFrame: (() -> Unit)? = null
  private var releaseFrame: ((Boolean) -> Unit)? = null

  val hasFrame: Boolean
    get() = releaseFrame != null

  fun holdFrame(release: (Boolean) -> Unit) {
    check(releaseFrame == null) { "The previous frame is still acquired" }
    releaseFrame = release
  }

  /** Makes [context] ready for another access to the shared target. */
  fun prepare(context: DirectContext, contextReplaced: () -> Unit) {
    val previousContext = currentContext
    val pendingFrame = preserveFrame
    if (previousContext != null && previousContext !== context) {
      preserveFrame = null
      contextReplaced()
    } else if (pendingFrame != null) {
      pendingFrame()
      context.flush()
      context.submit(syncCpu = true)
      preserveFrame = null
    }
    releaseFrame?.invoke(true)
    releaseFrame = null
    currentContext = context
  }

  /**
   * Calls [contextReplaced] when Compose draws with a context other than the last prepared one, so
   * the host drops wrappers built for the old context. The held frame stays acquired: Compose still
   * reads it, and the next [prepare] releases it.
   */
  fun adoptContext(context: DirectContext, contextReplaced: () -> Unit) {
    val previousContext = currentContext
    if (previousContext == null || previousContext === context) return
    preserveFrame = null
    contextReplaced()
    currentContext = context
  }

  /** Records that Compose will read the target when it replays the current picture. */
  fun frameRecorded(preserve: () -> Unit) {
    check(currentContext != null) { "The Compose context was not prepared" }
    preserveFrame = preserve
  }

  /** Forgets work tied to a context the host has replaced. */
  fun abandon() {
    preserveFrame = null
    currentContext = null
    releaseFrame?.invoke(false)
    releaseFrame = null
  }
}
