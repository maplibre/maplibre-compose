package org.maplibre.compose.demoapp.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal actual val agentPlatformName: String = "desktop"

/**
 * The [AgentScreenshotRecorder] at the root of the app, provided by the desktop entry point so the
 * agent driver can capture the window. Null on desktop hosts that don't provide one (compose-glfw,
 * Nucleus Tao), where screenshots are unsupported.
 */
val LocalAgentScreenshotRecorder = compositionLocalOf<AgentScreenshotRecorder?> { null }

@Composable
internal actual fun rememberAgentScreenshotCapture(): (suspend () -> ByteArray)? {
  val recorder = LocalAgentScreenshotRecorder.current
  return remember(recorder) { recorder?.let { it::capture } }
}

/** Creates an [AgentScreenshotRecorder] whose [GraphicsLayer] is released with the composition. */
@Composable
fun rememberAgentScreenshotRecorder(runOnGpuThread: (Runnable) -> Unit): AgentScreenshotRecorder {
  val layer = rememberGraphicsLayer()
  return remember(layer) { AgentScreenshotRecorder(layer, runOnGpuThread) }
}

private const val CAPTURE_TIMEOUT_MS = 10_000L

/**
 * Captures the content under [modifier] as PNGs by recording a frame into a [GraphicsLayer] on
 * demand. Recording replays the Compose draw commands, including the map's shared-texture image, so
 * the capture matches what the window shows without screen-capture permissions. It still needs the
 * frame pipeline to draw: if frames stop being scheduled, [capture] times out and reports a 503
 * instead of an image. All entry points run on the UI thread; the agent server already dispatches
 * there.
 */
class AgentScreenshotRecorder
internal constructor(
  private val layer: GraphicsLayer,
  private val runOnGpuThread: (Runnable) -> Unit,
) {
  /** Bumped to schedule a frame when a capture is pending; read by [modifier]'s draw. */
  private var frameGeneration by mutableLongStateOf(0L)

  /** Completed by [modifier]'s draw once it records the pending capture. */
  private var pendingCapture: CompletableDeferred<Unit>? = null

  /**
   * Attach to the layout wrapping the content to capture. Draws the content normally, except with a
   * [capture] pending, when it records the frame into the layer and draws that instead.
   */
  val modifier: Modifier = Modifier.drawWithContent {
    frameGeneration // load-bearing read: capture() bumps it to schedule a frame
    val pending = pendingCapture
    if (pending == null) {
      drawContent()
    } else {
      // The DrawScope record() extension retargets drawContent() at the recording canvas; the
      // GraphicsLayer.record() member would draw it to the frame canvas and record nothing.
      layer.record { this@drawWithContent.drawContent() }
      pendingCapture = null
      pending.complete(Unit)
      drawLayer(layer)
    }
  }

  /** Captures the next frame as PNG bytes. */
  internal suspend fun capture(): ByteArray {
    if (pendingCapture != null)
      throw AgentException(409, "a screenshot capture is already in flight")
    val pending = CompletableDeferred<Unit>()
    pendingCapture = pending
    frameGeneration += 1
    try {
      withTimeout(CAPTURE_TIMEOUT_MS) { pending.await() }
    } catch (e: TimeoutCancellationException) {
      pendingCapture = null
      throw AgentException(503, "timed out waiting for a frame to capture")
    } catch (e: CancellationException) {
      pendingCapture = null
      throw e
    }
    // toImageBitmap() reads the map's GPU texture back through Skia's context, which the host
    // guards with its render lock.
    var png: ByteArray? = null
    runOnGpuThread(Runnable { png = encodePng() })
    return checkNotNull(png)
  }

  private fun encodePng(): ByteArray {
    val bitmap = runBlocking { layer.toImageBitmap() }
    val out = ByteArrayOutputStream()
    ImageIO.write(bitmap.toAwtImage(), "png", out)
    return out.toByteArray()
  }
}
