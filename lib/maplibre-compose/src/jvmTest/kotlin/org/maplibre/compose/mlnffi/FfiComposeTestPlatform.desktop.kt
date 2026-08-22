package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.EventQueue
import org.maplibre.compose.map.LocalMlnFfiMapHostFactory
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  val watchdog = startHangWatchdog()
  try {
    runComposeUiTest { block() }
  } finally {
    watchdog.interrupt()
    // Tests share a JVM; a dump left printing here would interleave into the next test's output.
    watchdog.join()
    MlnFfiApplication.resetForTest()
  }
}

/**
 * How long a test may run before the watchdog dumps every thread's stack. Just under the one-minute
 * `runTest` watchdog, which reports only its own cancellation machinery.
 */
private const val HANG_DUMP_DELAY_MILLIS = 50_000L

/** Attributes a hang to a stack trace before `runTest` cancels the test body anonymously. */
private fun startHangWatchdog(): Thread {
  val watchdog = Thread {
    try {
      Thread.sleep(HANG_DUMP_DELAY_MILLIS)
    } catch (_: InterruptedException) {
      return@Thread
    }
    System.err.println(
      "An FFI Compose test has run for ${HANG_DUMP_DELAY_MILLIS} ms; dumping all threads:"
    )
    for ((thread, stack) in Thread.getAllStackTraces()) {
      System.err.println(thread)
      for (frame in stack) System.err.println("\tat $frame")
    }
  }
  watchdog.name = "ffi-test-hang-watchdog"
  watchdog.isDaemon = true
  watchdog.start()
  return watchdog
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MlnFfiRuntimeOptions,
  content: @Composable () -> Unit,
) {
  MlnFfiApplication.configure(runtimeOptions)
  val preparedFactory = CurrentRuntimeTestMapHostFactory.prepare()
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
private constructor(private var preparedDriver: FfiTestRenderDriver?) : MlnFfiMapHostFactory {
  override val backends: RenderBackendPair =
    Maplibre.supportedRenderBackends()
      .map {
        when (it) {
          RenderBackend.METAL ->
            RenderBackendPair(MapRenderBackend.METAL, ComposeRenderBackend.METAL)
          RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.VULKAN, composeBackend())
          else -> error("No Desktop test map host for $it")
        }
      }
      .single()

  override val description: String = "production $backends test bridge"

  override fun create(): MlnFfiMapHostResult {
    val driver =
      preparedDriver
        ?: return MlnFfiMapHostResult.Failed(
          "The prepared Desktop test bridge was already consumed; each test map may create one host"
        )
    preparedDriver = null
    return MlnFfiMapHostResult.Created(driver)
  }

  fun closePendingDriver() {
    preparedDriver?.close()
    preparedDriver = null
  }

  fun requireConsumed() {
    if (preparedDriver == null) return
    closePendingDriver()
    error("The test content did not create a Desktop map host during initial composition")
  }

  private fun composeBackend(): ComposeRenderBackend =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      ComposeRenderBackend.DIRECT3D12
    } else {
      ComposeRenderBackend.OPENGL
    }

  companion object {
    fun prepare(): CurrentRuntimeTestMapHostFactory {
      check(!EventQueue.isDispatchThread()) {
        "The Desktop test bridge must be prepared off the EDT"
      }
      return CurrentRuntimeTestMapHostFactory(FfiTestPlatform.createRenderDriver())
    }
  }
}
