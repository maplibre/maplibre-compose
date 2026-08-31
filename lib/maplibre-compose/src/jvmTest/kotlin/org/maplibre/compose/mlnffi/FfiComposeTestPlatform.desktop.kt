package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicLong
import org.maplibre.compose.map.LocalMlnFfiMapHostFactory
import org.maplibre.compose.map.ProcessNativeMapRuntime
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  val watchdog = startHangWatchdog()
  try {
    runComposeUiTest {
      try {
        block()
      } finally {
        disposeFfiTestContent()
      }
    }
  } finally {
    watchdog.interrupt()
    // Tests share a JVM; a dump left printing here would interleave into the next test's output.
    // Bounded, so a wedged stderr could never hold up teardown for the daemon thread's sake.
    watchdog.join(1_000)
    ProcessNativeMapRuntime.resetForTest()
    MlnFfiApplication.resetForTest()
  }
}

/**
 * How long a wait may stay silent before the watchdog dumps every thread's stack.
 *
 * [pingFfiTestHangWatchdog] moves the deadline, so a long test with many waits is not dumped from
 * process start.
 */
private const val HANG_DUMP_DELAY_MILLIS = 50_000L

private val hangDeadlineMillis = AtomicLong(0L)

internal actual fun pingFfiTestHangWatchdog(timeoutMillis: Long) {
  hangDeadlineMillis.set(System.currentTimeMillis() + timeoutMillis)
}

/** Attributes a hang to a stack trace so a blocked frame pump is not a silent 45-minute job. */
private fun startHangWatchdog(): Thread {
  pingFfiTestHangWatchdog(HANG_DUMP_DELAY_MILLIS)
  val watchdog = Thread {
    while (true) {
      val remaining = hangDeadlineMillis.get() - System.currentTimeMillis()
      if (remaining <= 0L) break
      try {
        Thread.sleep(remaining)
      } catch (_: InterruptedException) {
        return@Thread
      }
    }
    System.err.println(
      "An FFI Compose test has been silent for ${HANG_DUMP_DELAY_MILLIS} ms; dumping all threads:"
    )
    for ((thread, stack) in Thread.getAllStackTraces()) {
      System.err.println(thread)
      for (frame in stack) System.err.println("\tat $frame")
    }
    System.err.flush()
  }
  watchdog.name = "ffi-test-hang-watchdog"
  watchdog.isDaemon = true
  watchdog.start()
  return watchdog
}

@OptIn(ExperimentalTestApi::class)
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  MlnFfiApplication.configure(runtimeOptions)
  val preparedFactory = CurrentRuntimeTestMapHostFactory.prepare(presentationCount)
  try {
    setContent {
      CompositionLocalProvider(
        LocalMlnFfiMapHostFactory provides preparedFactory,
        content = content,
      )
    }
    preparedFactory.requireConsumed()
  } catch (error: Throwable) {
    preparedFactory.closePendingDriver()
    throw error
  }
}

/** Creates a production bridge for whichever runtime this Desktop test process packages. */
private class CurrentRuntimeTestMapHostFactory
private constructor(private val preparedDrivers: ArrayDeque<FfiTestRenderDriver>) :
  MlnFfiMapHostFactory {
  private val initialDriverCount = preparedDrivers.size
  override val bridges: List<RenderBackendPair> =
    listOf(
      when (val packaged = Maplibre.supportedRenderBackends().singleOrNull()) {
        RenderBackend.METAL -> RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
        RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.VULKAN, composeBackend())
        else -> error("No Desktop test map host for ${packaged ?: "no packaged runtime"}")
      }
    )

  override val description: String = "production ${bridges.single()} test bridge"

  override fun create(backends: RenderBackendPair): MlnFfiMapHostResult {
    val driver =
      preparedDrivers.removeFirstOrNull()
        ?: return MlnFfiMapHostResult.Failed(
          "The Desktop test used more presentation hosts than it prepared"
        )
    return MlnFfiMapHostResult.Created(driver)
  }

  fun closePendingDriver() {
    preparedDrivers.forEach(FfiTestRenderDriver::close)
    preparedDrivers.clear()
  }

  fun requireConsumed() {
    if (preparedDrivers.size == initialDriverCount) {
      closePendingDriver()
      error("The test content did not create a Desktop map host during initial composition")
    }
    closePendingDriver()
  }

  private fun composeBackend(): ComposeRenderBackend =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      ComposeRenderBackend.DIRECT3D12
    } else {
      ComposeRenderBackend.OPENGL
    }

  companion object {
    fun prepare(presentationCount: Int): CurrentRuntimeTestMapHostFactory {
      check(!EventQueue.isDispatchThread()) {
        "The Desktop test bridge must be prepared off the EDT"
      }
      require(presentationCount > 0) { "A map test must prepare at least one presentation host" }
      return CurrentRuntimeTestMapHostFactory(
        ArrayDeque(List(presentationCount) { FfiTestPlatform.createRenderDriver() })
      )
    }
  }
}
