package dev.sargunv.maplibrecompose.demoapp.ui.design

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import dev.sargunv.maplibrecompose.demoapp.generated.Res
import dev.sargunv.maplibrecompose.demoapp.generated.close_24px
import org.jetbrains.compose.resources.vectorResource

@Composable
fun BackButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(vectorResource(Res.drawable.close_24px), contentDescription = "Back")
  }
}
