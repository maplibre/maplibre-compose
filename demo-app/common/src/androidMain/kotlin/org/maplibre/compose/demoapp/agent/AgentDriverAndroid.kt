package org.maplibre.compose.demoapp.agent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

internal actual val agentPlatformName: String = "android"

private const val PIXEL_COPY_TIMEOUT_MS = 10_000L

@Composable
internal actual fun rememberAgentScreenshotCapture(): (suspend () -> ByteArray)? {
  val view = LocalView.current
  return remember(view) { { captureView(view) } }
}

/**
 * Captures [view] as PNG bytes. PixelCopy (API 26+) includes the map's hardware surface; older
 * devices fall back to a software draw, which may miss it.
 */
private suspend fun captureView(view: View): ByteArray {
  val bitmap = runCatching {
    Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
  }
    .getOrElse { throw AgentException(503, "the view has no size yet") }
  val window = view.context.findActivity()?.window
  val copied =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null) {
      try {
        withTimeout(PIXEL_COPY_TIMEOUT_MS) {
          suspendCancellableCoroutine { continuation ->
            // A synchronous throw here (e.g. an inactive window) falls through to the draw below.
            runCatching {
              PixelCopy.request(
                window,
                bitmap,
                { result -> if (continuation.isActive) continuation.resume(result) },
                Handler(Looper.getMainLooper()),
              )
            }
              .onFailure { if (continuation.isActive) continuation.resume(PixelCopy.ERROR_UNKNOWN) }
          }
        } == PixelCopy.SUCCESS
      } catch (e: TimeoutCancellationException) {
        false
      }
    } else {
      false
    }
  if (!copied) view.draw(Canvas(bitmap))
  val out = ByteArrayOutputStream()
  bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
  return out.toByteArray()
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
