package org.maplibre.compose.mlnffi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.maplibre.compose.desktop.skiko.HostOperatingSystem
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.LocalMlnFfiMapHostFactory
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.resetForTest
import org.maplibre.nativeffi.Maplibre
import org.maplibre.nativeffi.render.RenderBackend

@OptIn(ExperimentalTestApi::class)
internal actual fun runFfiComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  val watchdog = startHangWatchdog()
  var failure: Throwable? = null
  try {
    runComposeUiTest { block() }
  } catch (error: Throwable) {
    failure = error
    throw error
  } finally {
    try {
      DefaultMapRuntime.resetForTest(failure)
    } finally {
      watchdog.interrupt()
      // Tests share a JVM; a dump left printing here would interleave into the next test's output.
      // Bounded, so a wedged stderr could never hold up teardown for the daemon thread's sake.
      watchdog.join(1_000)
    }
  }
}

/**
 * How long a test may run before the watchdog dumps every thread's stack. Just under the one-minute
 * `runTest` watchdog, which reports only its own cancellation machinery.
 */
private const val HangDumpDelayMillis = 50_000L

/** Attributes a hang to a stack trace before `runTest` cancels the test body anonymously. */
private fun startHangWatchdog(): Thread {
  val watchdog = Thread {
    try {
      Thread.sleep(HangDumpDelayMillis)
    } catch (_: InterruptedException) {
      return@Thread
    }
    System.err.println(
      "An FFI Compose test has run for ${HangDumpDelayMillis} ms; dumping all threads:"
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
internal actual fun runPlainComposeUiTest(block: suspend ComposeUiTest.() -> Unit) {
  runComposeUiTest { block() }
}

@OptIn(ExperimentalTestApi::class)
internal actual fun ComposeUiTest.setFfiTestMapContent(
  runtimeOptions: MapRuntimeOptions,
  presentationCount: Int,
  content: @Composable () -> Unit,
) {
  DefaultMapRuntime.configure(from = runtimeOptions) {
    mainDispatcher = Dispatchers.Swing.immediate
  }
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
        RenderBackend.METAL -> RenderBackendPair(MapRenderBackend.Metal, ComposeRenderBackend.Metal)
        RenderBackend.VULKAN -> RenderBackendPair(MapRenderBackend.Vulkan, composeBackend())
        RenderBackend.OPENGL -> RenderBackendPair(MapRenderBackend.OpenGl, composeBackend())
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
    if (preparedDrivers.size < initialDriverCount) return
    closePendingDriver()
    error("The test content did not create a Desktop map host during initial composition")
  }

  private fun composeBackend(): ComposeRenderBackend =
    when (HostOperatingSystem.current()) {
      HostOperatingSystem.Macos -> ComposeRenderBackend.Metal
      HostOperatingSystem.Windows -> ComposeRenderBackend.Direct3D12
      HostOperatingSystem.Linux -> ComposeRenderBackend.OpenGl
      HostOperatingSystem.Unsupported -> error("Unsupported test platform")
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
