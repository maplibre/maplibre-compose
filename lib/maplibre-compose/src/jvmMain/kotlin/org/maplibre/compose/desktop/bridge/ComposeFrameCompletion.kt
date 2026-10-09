package org.maplibre.compose.desktop.bridge

import org.jetbrains.skia.DirectContext

/** Waits for Compose to finish reading the shared target before MapLibre writes it again. */
internal class ComposeFrameCompletion {
  private var currentContext: DirectContext? = null
  private var preserveFrame: (() -> Unit)? = null

  /** Detects context loss without copying an image that the consumer is only reading. */
  fun observe(context: DirectContext, contextReplaced: () -> Unit) {
    val previousContext = currentContext
    if (previousContext != null && previousContext !== context) {
      preserveFrame = null
      contextReplaced()
    }
    currentContext = context
  }

  /** Makes [context] ready before a producer can overwrite or release the shared target. */
  fun prepare(context: DirectContext, contextReplaced: () -> Unit) {
    observe(context, contextReplaced)
    val pendingFrame = preserveFrame
    if (pendingFrame != null) {
      pendingFrame()
      context.flush()
      context.submit(syncCpu = true)
      preserveFrame = null
    }
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
