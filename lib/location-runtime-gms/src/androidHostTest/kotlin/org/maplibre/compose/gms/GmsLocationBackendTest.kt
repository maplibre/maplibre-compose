package org.maplibre.compose.gms

import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.compose.location.AndroidLocationBackend

class GmsLocationBackendTest {

  @Test
  fun serviceLoaderFindsGmsBackend() {
    assertTrue(
      ServiceLoader.load(AndroidLocationBackend::class.java).any { it is GmsLocationBackend }
    )
  }

  @Test
  fun fusedBackendOutranksTheDefaultPriority() {
    assertEquals(100, GmsLocationBackend().priority)
  }
}
