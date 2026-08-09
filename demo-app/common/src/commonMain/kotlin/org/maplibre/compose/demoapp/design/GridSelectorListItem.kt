package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

@Composable
fun <T> GridSelectorListItem(
  options: List<List<T>>,
  selectedOption: T,
  onOptionSelected: (T) -> Unit,
  optionLabel: (T) -> String = { it.toString() },
  modifier: Modifier = Modifier,
) {
  options.forEach { row ->
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      row.forEach { option ->
        SelectableListItem(
          text = optionLabel(option),
          onClick = { onOptionSelected(option) },
          isSelected = selectedOption == option,
          textAlign = TextAlign.Center,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}
