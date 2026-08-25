package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class MlnFfiPathTest {
  @Test
  fun a_missing_file_still_gets_one_absolute_lexical_form() {
    val phantom = "${NSTemporaryDirectory()}mlnffi-missing-${NSUUID().UUIDString}"
    val missing = "$phantom/nested/../cache.db"
    assertEquals(
      Path("$phantom/cache.db").toString(),
      normalizeMlnFfiPath(Path(missing)).toString(),
    )
  }

  @Test
  fun two_spellings_of_the_same_location_compare_equal() {
    val phantom = "${NSTemporaryDirectory()}mlnffi-missing-${NSUUID().UUIDString}"
    val dotted = Path("$phantom/./nested/../cache.db")
    val plain = Path("$phantom/cache.db")
    assertEquals(normalizeMlnFfiPath(dotted), normalizeMlnFfiPath(plain))
  }

  @Test
  fun a_relative_path_resolves_against_the_current_directory() {
    val current = NSFileManager.defaultManager.currentDirectoryPath.trimEnd('/')
    assertEquals(
      Path("$current/cache.db").toString(),
      normalizeMlnFfiPath(Path("nested/../cache.db")).toString(),
    )
  }
}
