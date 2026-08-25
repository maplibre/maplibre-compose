package org.maplibre.compose.mlnffi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.io.files.Path

class MlnFfiPathTest {
  @Test
  fun a_missing_file_still_gets_one_absolute_lexical_form() {
    val phantom = File(File(".").canonicalFile, "mlnffi-missing-${System.nanoTime()}")
    val missing = File(phantom, "nested/../cache.db")
    assertFalse(missing.exists())
    assertEquals(File(phantom, "cache.db").path, normalizeMlnFfiPath(Path(missing.path)).toString())
  }

  @Test
  fun two_spellings_of_the_same_location_compare_equal() {
    val phantom = File(File(".").canonicalFile, "mlnffi-missing-${System.nanoTime()}")
    val dotted = Path(File(phantom, "./nested/../cache.db").path)
    val plain = Path(File(phantom, "cache.db").path)
    assertEquals(normalizeMlnFfiPath(dotted), normalizeMlnFfiPath(plain))
  }
}
