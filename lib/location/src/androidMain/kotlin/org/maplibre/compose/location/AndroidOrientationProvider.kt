package org.maplibre.compose.location

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.radians

/**
 * An [OrientationProvider] built on Android's
 * [`SensorManager`](https://developer.android.com/reference/android/hardware/SensorManager).
 *
 * It maps the azimuth from a
 * [rotation-vector sensor](https://developer.android.com/reference/android/hardware/Sensor#TYPE_ROTATION_VECTOR)
 * to [Orientation.orientation], and the sensor's estimated heading accuracy to
 * [BearingWithAccuracy.accuracy]. A sensor that reports its accuracy as unavailable maps to a
 * `null` accuracy.
 *
 * @param context Context used to obtain the platform sensor manager.
 * @param updateInterval Preferred minimum time between delivered headings.
 * @param coroutineScope Scope used to share the [orientation] flow.
 * @param sharingStarted Sharing policy for the [orientation] flow.
 */
@OptIn(FlowPreview::class)
public class AndroidOrientationProvider(
  context: Context,
  updateInterval: Duration,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : OrientationProvider {
  override val orientation: StateFlow<Orientation?>

  init {
    val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

    orientation =
      callbackFlow {
          val rotationMatrix = FloatArray(9)
          val orientationAngles = FloatArray(3)

          val listener =
            object : SensorEventListener {
              override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                  SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                  SensorManager.getOrientation(rotationMatrix, orientationAngles)

                  val azimuth = orientationAngles[0].toDouble().radians
                  // values[4] is the estimated heading accuracy, -1 if unavailable.
                  val accuracy = event.values.getOrNull(4)?.takeIf { it >= 0f }

                  trySend(
                    Orientation(
                      orientation =
                        BearingWithAccuracy(
                          value = Bearing.North + azimuth,
                          accuracy = accuracy?.toDouble()?.radians,
                        ),
                      timestamp = TimeSource.Monotonic.markNow(),
                    )
                  )
                }
              }

              override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

          val sensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
              ?: throw IllegalStateException("Rotation vector sensor is not available")

          sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            updateInterval.inWholeMicroseconds.toInt(),
            Handler(handlerThread.looper),
          )

          awaitClose { sensorManager.unregisterListener(listener) }
        }
        .sample(updateInterval)
        .stateIn(coroutineScope, sharingStarted, null)
  }

  private companion object {
    private val handlerThread by lazy {
      HandlerThread("AndroidOrientationProvider").apply { start() }
    }
  }
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  val context = LocalContext.current
  val resolution = remember(context) { AndroidLocationBackendResolver.discover(context) }
  val backendProvider =
    (resolution as? AndroidBackendResolution.Discovered)
      ?.backend
      ?.rememberOrientationProvider(updateInterval)
  return backendProvider ?: rememberAndroidOrientationProvider(updateInterval = updateInterval)
}

/** Creates and remembers the default Android [OrientationProvider]. */
@Composable
public fun rememberAndroidOrientationProvider(
  updateInterval: Duration,
  context: Context = LocalContext.current,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): AndroidOrientationProvider {
  return remember(context, updateInterval, coroutineScope, sharingStarted) {
    AndroidOrientationProvider(
      context = context,
      updateInterval = updateInterval,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}
