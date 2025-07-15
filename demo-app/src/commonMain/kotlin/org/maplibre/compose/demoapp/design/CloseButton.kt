package org.maplibre.compose.demoapp.design

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.vectorResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.close_24px

@Composable
fun CloseButton(onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(vectorResource(Res.drawable.close_24px), contentDescription = "Close")
  }
}
