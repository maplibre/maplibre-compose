package org.maplibre.compose.map

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AndroidFrameTickerTest {
  @Test
  fun frames_keep_coming_from_the_timer_while_vsync_is_stopped() = runBlocking {
    withContext(Dispatchers.Main) {
      val ticker = AndroidFrameTicker(Handler(Looper.getMainLooper()), StoppedVsync)
      val frames = withTimeout(5_000) { List(10) { ticker.nextFrame() } }
      assertTrue(frames.zipWithNext().all { (a, b) -> b > a }, "Frame times $frames must increase")
    }
  }
}

/** A display whose Choreographer stopped, as a phone's does while its screen is off. */
internal object StoppedVsync : AndroidVsync {
  override fun postFrameCallback(callback: Choreographer.FrameCallback) {}

  override fun removeFrameCallback(callback: Choreographer.FrameCallback) {}
}

private suspend fun AndroidFrameTicker.nextFrame(): Long = suspendCancellableCoroutine {
  postFrame(it::resume)
  it.invokeOnCancellation { cancel() }
}
