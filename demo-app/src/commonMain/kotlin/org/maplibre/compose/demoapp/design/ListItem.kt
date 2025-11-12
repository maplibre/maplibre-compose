package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun SelectableListItem(
  text: String,
  onClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  trailingContent: @Composable (() -> Unit)? = null,
  textAlign: TextAlign? = null,
  modifier: Modifier = Modifier,
) {
  val backgroundColor =
    if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface

  val contentColor =
    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

  ListItem(
    headlineContent = {
      Text(
        text = text,
        textAlign = textAlign,
        modifier = if (textAlign != null) Modifier.fillMaxWidth() else Modifier,
      )
    },
    trailingContent = trailingContent,
    colors = ListItemDefaults.colors(backgroundColor, contentColor),
    modifier =
      modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
  )
}

@Composable
fun SliderListItem(
  text: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit = {},
  valueLabel: (Float) -> String = { it.toString() },
  modifier: Modifier = Modifier,
) {
  ListItem(
    headlineContent = { Text("$text: ${valueLabel(value)}") },
    supportingContent = {
      Slider(
        value = value,
        onValueChange = { onValueChange(it) },
        onValueChangeFinished = { onValueChangeFinished() },
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    modifier = modifier.fillMaxWidth(),
  )
}

@Composable
fun <T> SegmentedButtonListItem(
  options: List<T>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  optionLabel: (T) -> String = { it.toString() },
  modifier: Modifier = Modifier,
) {
  ListItem(
    headlineContent = {
      SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        options.forEach { option ->
          SegmentedButton(
            selected = option == selectedOption,
            onClick = { onOptionSelected(option) },
            shape =
              SegmentedButtonDefaults.itemShape(
                index = options.indexOf(option),
                count = options.size,
              ),
          ) {
            Text(text = optionLabel(option))
          }
        }
      }
    },
    modifier = modifier.fillMaxWidth(),
  )
}
