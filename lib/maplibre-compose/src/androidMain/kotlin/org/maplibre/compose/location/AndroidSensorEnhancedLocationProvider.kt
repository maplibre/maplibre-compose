package org.maplibre.compose.location

import android.Manifest
import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * A [LocationProvider] that enhances an existing [LocationProvider] with bearing information
 * derived from the device's rotation vector sensor.
 *
 * This class listens to the `Sensor.TYPE_ROTATION_VECTOR` to get the device's orientation. It then
 * combines this sensor-derived bearing with the location data from the wrapped [locationProvider].
 *
 * The sensor-based bearing is only used if its accuracy is better than the bearing accuracy
 * provided by the original location provider.
 *
 * @param context The application context, used to access the `SensorManager`.
 * @param locationProvider The underlying [LocationProvider] to be enhanced.
 * @param coroutineScope The [CoroutineScope] in which the location and bearing flows are combined.
 * @param sharingStarted The strategy for starting and stopping the collection of the location flow.
 *   Defaults to [SharingStarted.WhileSubscribed] with a 1-second stop timeout.
 * @throws IllegalStateException if the rotation vector sensor is not available on the device.
 */
public class AndroidSensorEnhancedLocationProvider(
  context: Context,
  locationProvider: LocationProvider,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : LocationProvider {
  private val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

  private fun accuracyToDegrees(accuracy: Int): Double =
    when (accuracy) {
      SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 5.0
      SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 15.0
      SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 45.0
      SensorManager.SENSOR_STATUS_UNRELIABLE -> 180.0
      else -> 180.0
    }

  private val bearing: Flow<Pair<Double, Double>> =
    callbackFlow {
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        var accuracyDegrees = accuracyToDegrees(SensorManager.SENSOR_STATUS_UNRELIABLE)

        val listener =
          object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
              if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                trySend(Math.toDegrees(orientationAngles[0].toDouble()) to accuracyDegrees)
              }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
              accuracyDegrees = accuracyToDegrees(accuracy)
            }
          }

        val sensor =
          sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: throw IllegalStateException("Rotation vector sensor is not available")

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        awaitClose { sensorManager.unregisterListener(listener) }
      }
      .buffer(capacity = 1)
      .flowOn(Dispatchers.Default)

  override val location: StateFlow<Location?> =
    locationProvider.location
      .combine(bearing) { location, (sensorBearing, sensorAccuracy) ->
        val bearingAccuracy = location?.bearingAccuracy
        if (location != null && (bearingAccuracy == null || bearingAccuracy > sensorAccuracy)) {
          location.copy(bearing = sensorBearing, accuracy = sensorAccuracy)
        } else {
          location
        }
      }
      .stateIn(coroutineScope, sharingStarted, null)
}

@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public actual fun rememberSensorEnhancedLocationProvider(
  locationProvider: LocationProvider
): LocationProvider {
  return rememberAndroidSensorEnhancedLocationProvider(locationProvider = locationProvider)
}

/**
 * Create and remember an [AndroidSensorEnhancedLocationProvider], a [LocationProvider] that
 * enhances an existing [LocationProvider] with bearing information derived from the device's
 * rotation vector sensor.
 */
@Composable
public fun rememberAndroidSensorEnhancedLocationProvider(
  locationProvider: LocationProvider,
  context: Context = LocalContext.current,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): AndroidSensorEnhancedLocationProvider {
  return remember(context, locationProvider, coroutineScope, sharingStarted) {
    AndroidSensorEnhancedLocationProvider(
      context = context,
      locationProvider = locationProvider,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}
