@file:OptIn(DelicateMaplibreComposeApi::class, ExperimentalMaplibreComposeApi::class)

package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.DelicateMaplibreComposeApi
import org.maplibre.compose.util.ExperimentalMaplibreComposeApi

class PlatformMapAccessTest {
  @Test
  fun access_waits_for_the_presented_map() = runMapTest {
    val fixture = BridgeMapFixture.create(present = false)
    try {
      val state = fixture.state
      val access = async(start = CoroutineStart.UNDISPATCHED) { state.withPlatformMap { map } }
      assertFalse(access.isCompleted)
      val next = state.reservePresentation()
      fixture.session.beginPresentationAttachment()
      fixture.session.attachPresentation()
      state.publishPresentation(next, fixture.session)
      val map = access.await()
      assertSame(assertNotNull(fixture.session.mapForTest), map)
    } finally {
      fixture.close()
    }
  }
}
