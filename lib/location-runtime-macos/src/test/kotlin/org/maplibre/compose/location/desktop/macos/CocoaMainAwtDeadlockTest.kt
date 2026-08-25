package org.maplibre.compose.location.desktop.macos

import java.awt.Canvas
import java.awt.Component
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CocoaMainAwtDeadlockTest {
  @Test
  fun cocoaMainCompletesWhileAppKitWaitsForAwtAccessibility() {
    if (!System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac")) return

    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val outputFile = Files.createTempFile("cocoa-main-awt-deadlock", ".log")
    try {
      val process =
        ProcessBuilder(
            java,
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            CocoaMainAwtDeadlockHarness::class.java.name,
          )
          .redirectErrorStream(true)
          .redirectOutput(outputFile.toFile())
          .start()
      try {
        val completed = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
          process.destroyForcibly()
          process.waitFor()
        }
        val output = Files.readString(outputFile)
        assertTrue(completed, "Child JVM timed out:\n$output")
        assertEquals(0, process.exitValue(), "Child JVM failed:\n$output")
      } finally {
        if (process.isAlive) process.destroyForcibly()
      }
    } finally {
      Files.deleteIfExists(outputFile)
    }
  }

  private companion object {
    const val CHILD_TIMEOUT_SECONDS = 10L
  }
}

internal object CocoaMainAwtDeadlockHarness {
  @JvmStatic
  fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    lateinit var component: Component
    EventQueue.invokeAndWait { component = Canvas() }

    val awtEnteredCocoaMain = CountDownLatch(1)
    val awtCompletedCocoaMain = CountDownLatch(1)
    CocoaMain.run {
      EventQueue.invokeLater {
        awtEnteredCocoaMain.countDown()
        CocoaMain.run {}
        awtCompletedCocoaMain.countDown()
      }
      check(awtEnteredCocoaMain.await(HARNESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
      queryAwtFocusFromAppKit(component)
    }

    check(awtCompletedCocoaMain.await(HARNESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
  }

  private fun queryAwtFocusFromAppKit(component: Component) {
    val accessibility = Class.forName("sun.lwawt.macosx.CAccessibility")
    accessibility
      .getDeclaredMethod("getFocusOwner", Component::class.java)
      .apply { isAccessible = true }
      .invoke(null, component)
  }

  private const val HARNESS_TIMEOUT_SECONDS = 5L
}
