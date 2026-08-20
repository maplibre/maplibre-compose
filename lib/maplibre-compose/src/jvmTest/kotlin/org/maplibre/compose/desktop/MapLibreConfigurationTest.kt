package org.maplibre.compose.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.MlnFfiApplication

class MapLibreConfigurationTest {

  @Test
  fun cache_paths_are_scoped_to_the_application() {
    val first = desktopCachePath("com.example.first")
    val second = desktopCachePath("com.example.second")

    assertEquals("com.example.first", first.parent.fileName.toString())
    assertEquals("maplibre-cache.db", first.fileName.toString())
    assertNotEquals(first, second)
  }

  @Test
  fun application_ids_cannot_escape_the_cache_directory() {
    assertFailsWith<IllegalArgumentException> { MapLibre.configure("../another-app") }
    assertFailsWith<IllegalArgumentException> { MapLibre.configure("com/example/app") }
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
  fun repeating_the_same_configuration_is_harmless() {
    try {
      MapLibre.configure("com.example.same")
      MapLibre.configure("com.example.same")
    } finally {
      MlnFfiApplication.resetForTest()
    }
  }

  @Test
  fun replacing_the_application_configuration_fails() {
    try {
      MapLibre.configure("com.example.first")

      assertFailsWith<IllegalStateException> { MapLibre.configure("com.example.second") }
    } finally {
      MlnFfiApplication.resetForTest()
    }
  }

  @Test
  fun replacing_the_cache_limit_fails() {
    try {
      MapLibre.configure("com.example.same", maximumCacheSizeBytes = 1_000)

      assertFailsWith<IllegalStateException> {
        MapLibre.configure("com.example.same", maximumCacheSizeBytes = 2_000)
      }
    } finally {
      MlnFfiApplication.resetForTest()
    }
  }

  @Test
  fun default_configuration_does_not_replace_an_existing_one() {
    try {
      MapLibre.configure("com.example.first")
      MlnFfiApplication.ensureConfigured { desktopRuntimeOptions("com.example.second") }
      val installed = MlnFfiApplication.options.cacheFile.toString()
      assertTrue(installed.contains("com.example.first"))
    } finally {
      MlnFfiApplication.resetForTest()
    }
  }

  @Test
  fun application_id_is_the_main_class_package() {
    assertEquals(
      "org.maplibre.compose.demoapp",
      applicationIdFromClassName("org.maplibre.compose.demoapp.MainKt"),
    )
    assertEquals("com.example.app", applicationIdFromClassName("com.example.app.DesktopApp"))
    assertNull(applicationIdFromClassName("MainKt"))
    assertNull(applicationIdFromClassName("com/example/app.MainKt"))
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
