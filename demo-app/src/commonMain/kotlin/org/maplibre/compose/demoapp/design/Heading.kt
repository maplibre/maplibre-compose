package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun Heading(
  text: String,
  modifier: Modifier = Modifier,
  trailingContent: @Composable (() -> Unit)? = null,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(bottom = 16.dp)) {
    Text(
      text = text,
      style = MaterialTheme.typography.headlineMedium,
      modifier = Modifier.weight(1f),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (trailingContent != null) {
      Spacer(modifier = Modifier.width(8.dp))
      trailingContent()
    }
  }
}
