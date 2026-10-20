package org.maplibre.compose.gms

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.location.AndroidLocationBackendFactory

class GmsLocationBackendTest {

  @Test
  fun serviceLoaderFindsGmsBackend() {
    assertTrue(
      ServiceLoader.load(AndroidLocationBackendFactory::class.java).any {
        it is GmsLocationBackendFactory
      },
    )
  }
}
