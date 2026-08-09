package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.DirectContext

/** Waits for Compose to finish reading the shared target before MapLibre writes it again. */
internal class ComposeFrameCompletion {
  private var currentContext: DirectContext? = null
  private var preserveFrame: (() -> Unit)? = null

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
  }
}
