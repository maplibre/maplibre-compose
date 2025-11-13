package org.maplibre.compose.location

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal class Heading(context: Context, handler: Handler, samplingPeriod: Int) {
  private val sensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager

  private fun accuracyToDegrees(accuracy: Int): Double =
    when (accuracy) {
      SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 5.0
      SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 15.0
      SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 45.0
      SensorManager.SENSOR_STATUS_UNRELIABLE -> 180.0
      else -> 180.0
    }

  @OptIn(FlowPreview::class)
  internal val heading: Flow<Pair<Double, Double>> = callbackFlow {
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

    sensorManager.registerListener(
      listener,
      sensor,
      SensorManager.SENSOR_DELAY_NORMAL,
      samplingPeriod,
      handler,
    )

    awaitClose { sensorManager.unregisterListener(listener) }
  }
}
