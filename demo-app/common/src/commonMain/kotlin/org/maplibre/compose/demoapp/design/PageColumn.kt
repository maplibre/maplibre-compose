package org.maplibre.compose.demoapp.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PageColumn(
  modifier: Modifier = Modifier.Companion,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scrollState = rememberScrollState()
  Column(modifier = modifier.padding(16.dp).verticalScroll(scrollState), content = content)
}
