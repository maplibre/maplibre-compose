package org.maplibre.compose.material3.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles

internal expect fun htmlToAnnotatedString(
  html: String,
  textLinkStyles: TextLinkStyles?,
): AnnotatedString
