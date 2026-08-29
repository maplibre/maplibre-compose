package org.maplibre.compose.map

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle

class MapStateHostConfinementTest {

  @Test
  fun a_background_baseStyle_write_hops_onto_the_host() {
    val host = RecordingHostDispatcher()
    val state =
      MapState(
        cameraPosition = CameraPosition(),
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        logger = null,
        hostDispatcher = host,
      )
    val dispatchesBefore = host.dispatches.get()
    val worker = Executors.newSingleThreadExecutor()
    try {
      worker.submit { state.baseStyle = STYLE }.get()
      assertTrue(
        host.dispatches.get() > dispatchesBefore,
        "a caller off the host must hop so the record stays single-threaded",
      )
      assertEquals(STYLE, state.baseStyle)
    } finally {
      worker.shutdown()
      state.close()
      host.close()
    }
  }

  private companion object {
    val STYLE = BaseStyle.Json("""{"version":8,"sources":{},"layers":[]}""")
  }
}

private class RecordingHostDispatcher : CoroutineDispatcher() {
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, HOST_THREAD).apply { isDaemon = true }
  }
  val dispatches = AtomicInteger(0)

  override fun isDispatchNeeded(context: CoroutineContext): Boolean =
    Thread.currentThread().name != HOST_THREAD

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    dispatches.incrementAndGet()
    executor.execute(block)
  }

  fun close() {
    executor.shutdown()
  }

  private companion object {
    const val HOST_THREAD = "map-state-host-confinement"
  }
}
