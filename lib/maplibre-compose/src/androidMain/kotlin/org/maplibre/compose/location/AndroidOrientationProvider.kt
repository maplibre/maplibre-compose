package org.maplibre.compose.location

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
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
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees

/**
 * A [OrientationProvider] built on the [SensorManager] platform APIs.
 *
 * @param context the [Context] get the [LocationManager] system service from
 * @param updateInterval the *minimum* time between location updates
 * @param coroutineScope the [CoroutineScope] used to share the [orientation] flow
 * @param sharingStarted parameter for [stateIn] call of [orientation]
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
    if (!handlerThread.isAlive) {
      handlerThread.start()
    }

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

                  val degrees = Math.toDegrees(orientationAngles[0].toDouble()).degrees

                  trySendBlocking(
                      Orientation(
                        orientation =
                          BearingMeasurement(
                            bearing = Bearing.North + degrees,
                            // we can not get accuracy in degrees
                            accuracy = null,
                          ),
                        timestamp = TimeSource.Monotonic.markNow(),
                      )
                    )
                    .getOrThrow()
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
    private val handlerThread by lazy { HandlerThread("AndroidOrientationProvider") }
  }
}

@Composable
public actual fun rememberDefaultOrientationProvider(
  updateInterval: Duration
): OrientationProvider {
  return rememberAndroidOrientationProvider(updateInterval = updateInterval)
}

/**
 * Create and remember an [AndroidOrientationProvider], the default [OrientationProvider] for
 * Android
 */
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
