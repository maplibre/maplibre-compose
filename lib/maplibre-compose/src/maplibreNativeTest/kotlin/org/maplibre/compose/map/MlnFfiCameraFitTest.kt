package org.maplibre.compose.map

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.testing.runMapTest
import org.maplibre.compose.util.DpPadding
import org.maplibre.nativeffi.generated.CameraOptions

class MlnFfiCameraFitTest {
  @Test
  fun a_fit_cancelled_by_map_close_does_not_use_the_released_map() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.awaitRendered()
      val map = checkNotNull(fixture.session.mapForTest)
      val fitted = CompletableDeferred<CameraOptions>()
      val query =
        async(start = CoroutineStart.UNDISPATCHED) {
          fixture.session.fitCamera(map, 0.0, 0.0, null, DpPadding.Zero) { fitted }
        }
      assertFalse(query.isCompleted)

      fixture.session.close()
      fixture.session.awaitClosed()
      fitted.complete(CameraOptions())

      assertFailsWith<CancellationException> { query.await() }
    }
  }
}
