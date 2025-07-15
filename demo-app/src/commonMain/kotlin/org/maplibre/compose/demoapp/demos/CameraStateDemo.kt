package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.roundToInt
import org.maplibre.compose.demoapp.DemoState
import org.maplibre.compose.demoapp.design.CardColumn
import org.maplibre.compose.demoapp.design.Subheading

object CameraStateDemo : Demo {
  override val name = "Camera State"

  @Composable
  override fun SheetContent(state: DemoState, modifier: Modifier) {
    val cameraState = state.cameraState

    Subheading(text = "Camera Position")
    CardColumn {
      val position = cameraState.position
      InfoRow("Zoom", position.zoom.toRoundedString(2))
      InfoRow("Bearing", "${position.bearing.toRoundedString(1)}°")
      InfoRow("Tilt", "${position.tilt.toRoundedString(1)}°")
      InfoRow("Target Longitude", "${position.target.longitude.toRoundedString(5)}°")
      InfoRow("Target Latitude", "${position.target.latitude.toRoundedString(5)}°")
    }

    Spacer(modifier = Modifier.height(8.dp))

    Subheading(text = "Camera State")
    CardColumn {
      InfoRow("Is Camera Moving", cameraState.isCameraMoving.toString())
      InfoRow("Move Reason", cameraState.moveReason.toString())
      InfoRow("Meters Per DP At Target", cameraState.metersPerDpAtTarget.toRoundedString(2))
    }
  }

  @Composable
  private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(16.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.weight(1f),
      )
    }
  }

  private fun Double.toRoundedString(numDigits: Int): String {
    val scaleFactor = 10.0.pow(numDigits)
    val digits = (this * scaleFactor).roundToInt().toString()
    return if (digits.length > numDigits) {
      "${digits.substring(0, digits.length - numDigits)}.${digits.takeLast(numDigits)}"
    } else {
      "0.${"0".repeat(numDigits - digits.length)}$digits"
    }
  }
}
