package dev.sargunv.maplibrecompose.demoapp.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SimpleListItem(
  text: String,
  onClick: () -> Unit,
  isSelected: Boolean = false,
  modifier: Modifier = Modifier.Companion,
) {
  val backgroundColor =
    if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface

  val contentColor =
    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

  ListItem(
    headlineContent = { Text(text) },
    modifier = modifier.clickable(onClick = onClick).fillMaxWidth(),
    colors = ListItemDefaults.colors(backgroundColor, contentColor),
  )
}
