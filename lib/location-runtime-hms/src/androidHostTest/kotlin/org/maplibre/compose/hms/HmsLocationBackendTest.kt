package org.maplibre.compose.hms

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.location.AndroidLocationBackendFactory

class HmsLocationBackendTest {

  @Test
  fun serviceLoaderFindsHmsBackend() {
    assertTrue(
      ServiceLoader.load(AndroidLocationBackendFactory::class.java).any {
        it is HmsLocationBackendFactory
      },
    )
  }
}
