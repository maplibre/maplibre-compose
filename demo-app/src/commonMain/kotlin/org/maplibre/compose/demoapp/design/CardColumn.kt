package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CardColumn(
  modifier: Modifier = Modifier.Companion,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    colors = CardDefaults.cardColors(),
  ) {
    Column(modifier = Modifier.Companion.fillMaxWidth()) { content() }
  }
}
