package org.maplibre.compose.map

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AndroidPresentationFrameClockTest {
  @Test
  fun frames_use_the_display_vsync_while_it_runs() = runBlocking {
    withContext(Dispatchers.Main) {
      val vsyncTimes = mutableSetOf<Long>()
      val recorder =
        object : Choreographer.FrameCallback {
          override fun doFrame(frameTimeNanos: Long) {
            vsyncTimes += frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
          }
        }
      Choreographer.getInstance().postFrameCallback(recorder)
      try {
        val clock = AndroidPresentationFrameClock(Handler(Looper.getMainLooper()))
        val frames = List(10) { clock.withFrameNanos { it } }
        assertTrue(vsyncTimes.containsAll(frames), "Frames $frames are not vsync times $vsyncTimes")
      } finally {
        Choreographer.getInstance().removeFrameCallback(recorder)
      }
    }
  }
}
