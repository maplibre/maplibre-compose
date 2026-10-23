package org.maplibre.compose.location

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidHeadingProviderTest {
  private val application = RuntimeEnvironment.getApplication()
  private val sensorManager = application.getSystemService(SensorManager::class.java)

  @Test
  fun missingSensorReportsUnsupportedAndCompletes() = runTest {
    assertEquals(
      listOf(HeadingEvent.Unavailable(LocationUnavailableReason.Unsupported)),
      AndroidHeadingProvider(application).updates().toList(),
    )
  }

  @Test
  fun refusedRegistrationReportsUnavailableAndCompletes() = runTest {
    shadowOf(sensorManager)
      .addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ROTATION_VECTOR).build())
    shadowOf(sensorManager).setForceListenersToFail(true)

    val event = AndroidHeadingProvider(application).updates().toList().single()

    assertNull(assertIs<HeadingEvent.Unavailable>(event).reason)
  }
}
