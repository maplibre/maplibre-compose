package org.maplibre.compose.location

import android.hardware.Sensor
import android.hardware.SensorManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder
import org.robolectric.shadows.SensorEventBuilder

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AndroidHeadingProviderTest {
  private val application = RuntimeEnvironment.getApplication()
  private val sensorManager = application.getSystemService(SensorManager::class.java)

  @Test
  fun missingSensorReportsUnsupportedAndCompletes() = runTest {
    assertEquals(
      listOf(HeadingEvent.Unavailable(HeadingUnavailableReason.Unsupported)),
      AndroidHeadingProvider(application).updates().toList(),
    )
  }

  @Test
  fun refusedRegistrationReportsUnavailableAndCompletes() = runTest {
    addRotationVectorSensor()
    shadowOf(sensorManager).setForceListenersToFail(true)

    val event = AndroidHeadingProvider(application).updates().toList().single()

    assertNull(assertIs<HeadingEvent.Unavailable>(event).reason)
  }

  @Test
  fun unreliableReadingsReportUnreliableUntilAReliableReading() = runTest {
    val sensor = addRotationVectorSensor()
    val events = mutableListOf<HeadingEvent>()
    backgroundScope.launch {
      AndroidHeadingProvider(application)
        .updates(HeadingRequest { minimumInterval = Duration.ZERO })
        .toList(events)
    }
    runCurrent()

    for (accuracy in
      listOf(
        SensorManager.SENSOR_STATUS_UNRELIABLE,
        SensorManager.SENSOR_STATUS_UNRELIABLE,
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
      )) {
      shadowOf(sensorManager)
        .sendSensorEventToListeners(
          SensorEventBuilder.newBuilder(sensor, floatArrayOf(0f, 0f, 0f, 1f, 0.1f))
            .setAccuracy(accuracy)
            .build(),
        )
    }
    runCurrent()

    assertEquals(2, events.size)
    assertEquals(HeadingEvent.Unavailable(HeadingUnavailableReason.Unreliable), events[0])
    assertIs<HeadingEvent.Update>(events[1])
  }

  private fun addRotationVectorSensor(): Sensor =
    SensorBuilder.newBuilder().setType(Sensor.TYPE_ROTATION_VECTOR).build().also {
      shadowOf(sensorManager).addSensor(it)
    }
}
