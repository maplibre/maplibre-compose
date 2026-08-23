package org.maplibre.compose.hms

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.location.AndroidLocationBackend

class HmsLocationBackendTest {

  @Test
  fun serviceLoaderFindsHmsBackend() {
    assertTrue(
      ServiceLoader.load(AndroidLocationBackend::class.java).any { it is HmsLocationBackend }
    )
  }
}
