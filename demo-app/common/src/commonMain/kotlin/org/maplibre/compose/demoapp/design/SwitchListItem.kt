package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SwitchListItem(
  text: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Spacer(modifier = Modifier.width(16.dp))
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}
