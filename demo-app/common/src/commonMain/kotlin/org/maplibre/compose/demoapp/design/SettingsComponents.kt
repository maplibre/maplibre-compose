package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  ListItem(
    headlineContent = { Text(label) },
    trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) }),
  )
}

/** A single-choice row of short options, as a settings list item. */
@Composable
fun <T> SegmentedRow(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      options.forEachIndexed { index, option ->
        SegmentedButton(
          selected = option == selected,
          onClick = { onSelect(option) },
          shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
          label = { Text(optionLabel(option), maxLines = 1) },
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/**
 * The frame rate cap choices every platform's render options offer. Null is no cap, so the map
 * follows the display refresh rate.
 */
@Composable
fun FpsCapRow(maximumFps: Int?, onSelect: (Int?) -> Unit) {
  SegmentedRow(
    label = "Frame rate cap",
    options = listOf(null, 15, 30, 60, 120),
    selected = maximumFps,
    optionLabel = { it?.toString() ?: "Auto" },
    onSelect = onSelect,
  )
}
