package org.maplibre.compose.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
}
