package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Subheading(text: String, modifier: Modifier = Modifier.Companion) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleLarge,
    modifier = modifier.padding(vertical = 8.dp),
  )
}
