package org.maplibre.compose.demoapp.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.maplibre.compose.demoapp.design.CloseButton
import org.maplibre.compose.demoapp.design.Heading
import org.maplibre.compose.demoapp.design.PageColumn

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SynchronousGeoJsonUpdatesDemoSheetPreview() {
  MaterialTheme {
    PageColumn {
      Heading(text = "Synchronous GeoJSON updates", trailingContent = { CloseButton {} })
      SynchronousGeoJsonUpdatesDemoSheet(
        synchronousUpdateEnabled = true,
        followCameraEnabled = true,
        zoomLevel = 18,
        onSynchronousUpdateChange = {},
        onFollowCameraChange = {},
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SynchronousGeoJsonUpdatesMapOverlayPreview() {
  MaterialTheme {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE7EEF4))) {
      SynchronousGeoJsonUpdatesMapOverlay(
        onZoomIn = {},
        onZoomOut = {},
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}
