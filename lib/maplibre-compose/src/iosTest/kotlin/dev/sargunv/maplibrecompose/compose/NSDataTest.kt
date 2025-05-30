package dev.sargunv.maplibrecompose.compose

import dev.sargunv.maplibrecompose.core.util.toByteArray
import dev.sargunv.maplibrecompose.core.util.toNSData
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test

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
