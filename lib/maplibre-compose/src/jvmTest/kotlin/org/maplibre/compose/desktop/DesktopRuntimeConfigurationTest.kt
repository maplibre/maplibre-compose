package org.maplibre.compose.desktop

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle

class DesktopRuntimeConfigurationTest {

  @Test
  fun cache_paths_are_scoped_to_the_application() {
    val first = desktopCachePath("com.example.first")
    val second = desktopCachePath("com.example.second")

    assertEquals("com.example.first", first.parent.fileName.toString())
    assertEquals("maplibre-cache.db", first.fileName.toString())
    assertNotEquals(first, second)
  }

  @Test
  fun cache_environment_paths_must_be_absolute() {
    val absolute = Paths.get(System.getProperty("user.home")).toAbsolutePath()

    assertNull(absoluteEnvironmentPath(null))
    assertNull(absoluteEnvironmentPath(""))
    assertNull(absoluteEnvironmentPath("relative/cache"))
    assertEquals(absolute, absoluteEnvironmentPath(absolute.toString()))
  }

  @Test
  fun independently_configured_runtimes_coexist_and_close_independently() = runTest {
    val root = Files.createTempDirectory("runtime caches")
    val first =
      createMapRuntime(
        MapRuntimeOptions(cacheFile = Path(root.resolve("first/cache.db").toString()))
      )
    val second =
      createMapRuntime(
        MapRuntimeOptions(
          cacheFile = Path(root.resolve("second/cache.db").toString()),
          maximumCacheSizeBytes = 2_000,
        )
      )
    val firstState = first.createMapState(BaseStyle.Demo)
    val secondState = second.createMapState(BaseStyle.Demo)

    assertNotSame(first.offlineManager, second.offlineManager)

    first.close()
    first.awaitClosed()

    assertTrue(firstState.isClosed)
    assertTrue(!secondState.isClosed)
    second.createMapState(BaseStyle.Demo).close()
    second.close()
    second.awaitClosed()
    root.toFile().deleteRecursively()
  }

  @Test
  fun application_id_is_the_main_class_package() {
    assertEquals(
      "org.maplibre.compose.demoapp",
      applicationIdFromClassName("org.maplibre.compose.demoapp.MainKt"),
    )
    assertEquals("com.example.app", applicationIdFromClassName("com.example.app.DesktopApp"))
    assertNull(applicationIdFromClassName("MainKt"))
  }

  @Test
  fun stack_walk_uses_the_process_entry_main() {
    val frames =
      listOf(
        StackTraceElement("java.lang.Object", "wait", "Object.java", 1),
        StackTraceElement(
          "androidx.compose.ui.window.Application_desktopKt",
          "application",
          null,
          1,
        ),
        StackTraceElement("org.maplibre.compose.demoapp.MainKt", "main", "Main.kt", 9),
        StackTraceElement(
          "com.intellij.rt.execution.application.AppMainV2",
          "main",
          "AppMainV2.java",
          50,
        ),
      )

    assertEquals("org.maplibre.compose.demoapp.MainKt", mainClassNameFromFrames(frames))
    assertEquals(
      "org.maplibre.compose.demoapp",
      applicationIdFromClassName(mainClassNameFromFrames(frames)!!),
    )
  }

  @Test
  fun stack_walk_prefers_the_thread_named_main() {
    val appMain =
      arrayOf(
        StackTraceElement("java.lang.Object", "wait", "Object.java", 1),
        StackTraceElement("org.maplibre.compose.demoapp.MainKt", "main", "Main.kt", 9),
      )
    val workerMain =
      arrayOf(
        StackTraceElement(
          "org.junit.platform.console.ConsoleLauncher",
          "main",
          "ConsoleLauncher.java",
          1,
        )
      )
    val traces = mapOf(Thread({}, "main") to appMain, Thread({}, "Test worker") to workerMain)

    assertEquals("org.maplibre.compose.demoapp.MainKt", mainClassNameFromStackTraces(traces))
  }
}
