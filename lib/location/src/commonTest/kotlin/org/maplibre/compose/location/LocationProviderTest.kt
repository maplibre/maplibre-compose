package org.maplibre.compose.location

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LocationProviderTest {
  @Test
  fun unsupportedProviderRejectsUpdateCollection() = runTest {
    assertFailsWith<IllegalStateException> {
      UnsupportedLocationProvider.updates(LocationRequest()).first()
    }
  }
}
