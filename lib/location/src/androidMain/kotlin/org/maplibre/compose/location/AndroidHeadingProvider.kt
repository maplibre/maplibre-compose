package org.maplibre.compose.location

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.radians

/**
 * A [HeadingProvider] built on Android's
 * [`SensorManager`](https://developer.android.com/reference/android/hardware/SensorManager).
 *
 * It maps the azimuth from a
 * [rotation-vector sensor](https://developer.android.com/reference/android/hardware/Sensor#TYPE_ROTATION_VECTOR)
 * to [Heading.bearing] with [HeadingReference.MagneticNorth]. A sensor that reports its accuracy as
 * unavailable maps to a `null` [Heading.accuracy].
 *
 * @param context Context used to obtain the platform sensor manager.
 */
@OptIn(FlowPreview::class)
public class AndroidHeadingProvider(context: Context) : HeadingProvider {
  private val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

  override fun updates(request: HeadingRequest): Flow<Heading> = callbackFlow {
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (sensor == null) {
      close()
      return@callbackFlow
    }
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
              Heading(
                bearing = Bearing.North + azimuth,
                reference = HeadingReference.MagneticNorth,
                accuracy = accuracy?.toDouble()?.radians,
                measuredAt =
                  Clock.System.now() -
                    (SystemClock.elapsedRealtimeNanos() - event.timestamp)
                      .coerceAtLeast(0)
                      .nanoseconds,
              )
            )
          }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
      }

    val registered =
      sensorManager.registerListener(
        listener,
        sensor,
        SensorManager.SENSOR_DELAY_NORMAL,
        request.minimumInterval.inWholeMicroseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        Handler(handlerThread.looper),
      )
    if (!registered) {
      close(IllegalStateException("Android rotation-vector sensor registration failed"))
      return@callbackFlow
    }

    awaitClose { sensorManager.unregisterListener(listener) }
  }
    .let { updates ->
      if (request.minimumInterval == Duration.ZERO) updates
      else updates.sample(request.minimumInterval)
    }

  private companion object {
    private val handlerThread by lazy {
      HandlerThread("AndroidHeadingProvider").apply { start() }
    }
  }
}

/**
 * Creates the default Android heading provider: the discovered backend's provider when one is
 * available, and the sensor-based [AndroidHeadingProvider] otherwise.
 */
public fun createDefaultHeadingProvider(context: Context): HeadingProvider =
  (AndroidLocationBackendResolver.discover(context) as? AndroidBackendResolution.Discovered)
    ?.backend
    ?.createHeadingProvider(context) ?: AndroidHeadingProvider(context)
