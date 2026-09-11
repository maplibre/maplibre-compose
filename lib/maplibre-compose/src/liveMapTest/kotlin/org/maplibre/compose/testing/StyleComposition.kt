package org.maplibre.compose.testing

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.compose.style.DesiredStyleRevision
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.RecordingStyleBinding
import org.maplibre.compose.style.StyleContent
import org.maplibre.compose.style.StyleNode
import org.maplibre.compose.style.StyleReconciler
import org.maplibre.compose.util.MaplibreComposable

/**
 * Composes [content] once against [style] and applies the revision it publishes, then returns
 * [style] with everything the composition installed. With [thenChange], runs it, recomposes, and
 * applies the revision that follows. [onRevision] sees each revision the composition publishes.
 */
internal suspend fun composeStyle(
  style: RecordingStyleBinding = RecordingStyleBinding(),
  thenChange: (() -> Unit)? = null,
  graphicsContext: GraphicsContext? = null,
  onRevision: (DesiredStyleRevision) -> Unit = {},
  awaitRevision: (DesiredStyleRevision) -> Boolean = { true },
  content: @Composable @MaplibreComposable () -> Unit,
): RecordingStyleBinding {
  val frameClock = BroadcastFrameClock()
  withContext(frameClock) {
    withRunningRecomposer { recomposer ->
      val rootNode = StyleNode(style)
      var revision: DesiredStyleRevision? = null
      val composition = Composition(MapNodeApplier(rootNode), recomposer)
      try {
        composition.setContent {
          CompositionLocalProvider(
            *if (graphicsContext != null) arrayOf(LocalGraphicsContext provides graphicsContext)
            else emptyArray(),
            LocalDensity provides Density(1f),
            LocalLayoutDirection provides LayoutDirection.Ltr,
          ) {
            StyleContent(
              rootNode,
              publish = {
                revision = it
                onRevision(it)
              },
              content = content,
            )
          }
        }
        val reconciler = StyleReconciler()
        var frame = 0L
        suspend fun pumpFrames() {
          do {
            // No UI host flushes global snapshot writes here, so a state an effect wrote reaches
            // the recomposer only through this call.
            Snapshot.sendApplyNotifications()
            if (frameClock.hasAwaiters) frameClock.sendFrame(frame++)
            delay(1)
          } while (recomposer.hasPendingWork || revision?.let(awaitRevision) != true)
          recomposer.awaitIdle()
        }
        pumpFrames()
        reconciler.apply(style, requireNotNull(revision))
        if (thenChange != null) {
          thenChange()
          Snapshot.sendApplyNotifications()
          pumpFrames()
          reconciler.apply(style, requireNotNull(revision))
        }
      } finally {
        composition.dispose()
      }
    }
  }
  return style
}
