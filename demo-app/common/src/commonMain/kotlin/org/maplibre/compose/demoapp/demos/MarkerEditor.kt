package org.maplibre.compose.demoapp.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.demoapp.generated.check_24px

@Composable
internal fun MarkerEditor(marker: EditableMarker, onClose: () -> Unit) {
  var showColors by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  fun closeEditor() {
    onClose()
    focusManager.clearFocus()
  }

  // Edits are live; closing only dismisses the controls and keyboard.
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerLowest,
    shape = MaterialTheme.shapes.medium,
    shadowElevation = 4.dp,
  ) {
    Column {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
          value = marker.label,
          onValueChange = { marker.label = it.take(60) },
          singleLine = true,
          textStyle =
            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { closeEditor() }),
          modifier =
            Modifier.weight(1f)
              .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
              .semantics { contentDescription = "Place name" }
              .onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                  closeEditor()
                  true
                } else false
              },
          decorationBox = { field ->
            Box {
              if (marker.label.isEmpty())
                Text("Place name", color = MaterialTheme.colorScheme.onSurfaceVariant)
              field()
            }
          },
        )

        IconButton(onClick = { showColors = !showColors }) {
          Box(
            Modifier.size(24.dp)
              .background(markerColorScheme(marker.color).primary, CircleShape)
              .semantics {
                contentDescription = "Change pin color"
              }
          )
        }
        IconButton(onClick = ::closeEditor) {
          Icon(painterResource(Res.drawable.check_24px), "Done editing")
        }
      }

      // Color choices stay out of the way until requested.
      AnimatedVisibility(showColors) {
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          for (choice in MarkerColor.entries) {
            val swatch = markerColorScheme(choice)
            Box(
              Modifier.size(48.dp)
                .clip(CircleShape)
                .selectable(
                  selected = marker.color == choice,
                  role = Role.RadioButton,
                  onClick = {
                    marker.color = choice
                    marker.bounce++
                    showColors = false
                  },
                )
                .semantics { contentDescription = "${choice.label} pin" },
              contentAlignment = Alignment.Center,
            ) {
              Box(
                Modifier.size(32.dp).background(swatch.primary, CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                if (marker.color == choice)
                  Icon(
                    painterResource(Res.drawable.check_24px),
                    null,
                    tint = swatch.onPrimary,
                    modifier = Modifier.size(20.dp),
                  )
              }
            }
          }
        }
      }
    }
  }
}
