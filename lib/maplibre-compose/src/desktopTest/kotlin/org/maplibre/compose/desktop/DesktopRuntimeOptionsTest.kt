package org.maplibre.compose.desktop

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.mlnffi.MlnFfiApplication

class DesktopRuntimeOptionsTest {

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
    assertFailsWith<IllegalArgumentException> { desktopCachePath("../another-app") }
    assertFailsWith<IllegalArgumentException> { desktopCachePath("com/example/app") }
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
  fun repeating_the_same_normalized_configuration_is_harmless() {
    val path = FfiTestPlatform.createCachePath()
    val alias = path.parent.resolve("unused").resolve("..").resolve(path.fileName)
    try {
      MapLibre.configure(DesktopRuntimeOptions(path))
      MapLibre.configure(DesktopRuntimeOptions(alias))
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCachePath(path)
    }
  }

  @Test
  fun replacing_the_application_configuration_fails() {
    val path = FfiTestPlatform.createCachePath()
    try {
      MapLibre.configure(DesktopRuntimeOptions(path))

      assertFailsWith<IllegalStateException> {
        MapLibre.configure(DesktopRuntimeOptions(path, maximumCacheSizeBytes = 1024))
      }
    } finally {
      MlnFfiApplication.resetForTest()
      FfiTestPlatform.deleteCachePath(path)
    }
  }
}
