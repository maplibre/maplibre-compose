package org.maplibre.compose.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
}
