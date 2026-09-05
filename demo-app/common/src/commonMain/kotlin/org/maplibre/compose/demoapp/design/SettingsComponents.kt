package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.arrow_drop_down_24px
import org.maplibre.compose.demoapp.generated.check_24px

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

/** A settings list item that opens a single-choice menu. */
@Composable
fun <T> DropdownRow(
  label: String,
  options: List<T>,
  selected: T,
  optionLabel: (T) -> String,
  onSelect: (T) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  var anchorWidth by remember { mutableIntStateOf(0) }
  val menuWidth = with(LocalDensity.current) { anchorWidth.toDp() }
  Box(Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = { Text(label) },
      supportingContent = { Text(optionLabel(selected)) },
      trailingContent = {
        Icon(
          imageVector = vectorResource(Res.drawable.arrow_drop_down_24px),
          contentDescription = null,
          modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      modifier =
        Modifier.fillMaxWidth()
          .onSizeChanged { anchorWidth = it.width }
          .clickable(role = Role.Button) {
            expanded = !expanded
          },
    )
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
      modifier = Modifier.width(menuWidth),
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(optionLabel(option)) },
          onClick = {
            onSelect(option)
            expanded = false
          },
          trailingIcon =
            if (option == selected) {
              {
                Icon(
                  imageVector = vectorResource(Res.drawable.check_24px),
                  contentDescription = null,
                )
              }
            } else {
              null
            },
          modifier = Modifier.semantics { this.selected = option == selected },
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
    options = listOf(null, 30, 60, 120),
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
