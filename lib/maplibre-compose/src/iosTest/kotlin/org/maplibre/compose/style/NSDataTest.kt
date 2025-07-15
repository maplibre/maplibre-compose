package org.maplibre.compose.style

import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import org.maplibre.compose.core.util.toByteArray
import org.maplibre.compose.core.util.toNSData

@OptIn(ExperimentalNativeApi::class)
class NSDataTest {
  @Test
  fun convertEmpty() {
    val empty = ByteArray(0)
    assert(empty.toNSData().toByteArray().contentEquals(empty))
  }

  @Test
  fun convert() {
    val array = "hello world".encodeToByteArray()
    assert(array.toNSData().toByteArray().contentEquals(array))
  }
}
