package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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

@Composable
fun ButtonRow(label: String, onClick: () -> Unit) {
  ListItem(
    headlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
  )
}

/** A single-choice dropdown of named options, as a settings list item. */
@Composable
fun <T> DropdownRow(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    OutlinedTextField(
      value = optionLabel(selected),
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier =
        Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          onClick = {
            onSelect(option)
            expanded = false
          },
        )
      }
    }
  }
}

/** A single-choice row of short options, as a settings list item. */
@Composable
fun <T> SegmentedRow(
  label: String? = null,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    if (label != null) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
    }
    SingleChoiceSegmentedButtonRow(
      Modifier.fillMaxWidth().padding(top = if (label != null) 8.dp else 0.dp)
    ) {
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

/** A labeled continuous value with its current value on the trailing edge. */
@Composable
fun SliderRow(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  valueLabel: (Float) -> String = { it.roundToInt().toString() },
  onChange: (Float) -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyLarge)
      Text(
        valueLabel(value),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
  }
}
