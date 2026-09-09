package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.design.SliderRow
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.delete_24px

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EditableMarkersPanel(
  markers: List<EditableMarker>,
  selectedId: Int?,
  textScale: Float,
  onTextScaleChange: (Float) -> Unit,
  onSelect: (EditableMarker) -> Unit,
  onRemove: (EditableMarker) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text("Your places", style = MaterialTheme.typography.titleLarge)
    AnimatedVisibility(markers.all { it.removing }) {
      EmptyPlaces()
    }
    for (marker in markers) key(marker.id) {
      AnimatedVisibility(!marker.removing) {
        PlaceRow(
          marker,
          selectedId == marker.id,
          onSelect = { onSelect(marker) },
          onRemove = { onRemove(marker) },
        )
      }
    }
  }
  SliderRow(
    "Marker text size",
    textScale,
    1f..2f,
    { "${(it * 100).roundToInt()}%" },
    onTextScaleChange,
  )
  Text(
    "Labels grow; the space below each icon stays the same.",
    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
  )
}

@Composable
private fun EmptyPlaces() {
  Column(
    Modifier.fillMaxWidth().padding(vertical = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      rememberMarkerPainter(MaterialTheme.colorScheme),
      null,
      tint = Color.Unspecified,
      modifier = Modifier.size(40.dp, 48.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text("Drop your first pin", style = MaterialTheme.typography.titleMedium)
    Text(
      "Tap anywhere on the map to add a place.",
      Modifier.padding(top = 4.dp),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaceRow(
  marker: EditableMarker,
  selected: Boolean,
  onSelect: () -> Unit,
  onRemove: () -> Unit,
) {
  val dismiss = rememberSwipeToDismissBoxState()
  SwipeToDismissBox(
    state = dismiss,
    modifier = Modifier.padding(top = 8.dp).clip(MaterialTheme.shapes.medium),
    enableDismissFromStartToEnd = false,
    onDismiss = { onRemove() },
    backgroundContent = {
      Row(
        Modifier.fillMaxSize()
          .background(
            if (dismiss.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent
            else MaterialTheme.colorScheme.errorContainer
          )
          .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          painterResource(Res.drawable.delete_24px),
          null,
          tint = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
    },
  ) {
    ListItem(
      selected = selected,
      onClick = onSelect,
      content = {
        Text(
          marker.label.ifBlank { "Unnamed place" },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      leadingContent = {
        Icon(
          rememberMarkerPainter(markerColorScheme(marker.color)),
          null,
          tint = Color.Unspecified,
          modifier = Modifier.size(24.dp, 29.dp),
        )
      },
      trailingContent = {
        IconButton(onClick = onRemove) {
          Icon(
            painterResource(Res.drawable.delete_24px),
            "Delete ${marker.label.ifBlank { "place" }}",
          )
        }
      },
      colors =
        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
  }
}
