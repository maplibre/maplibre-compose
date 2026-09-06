package org.maplibre.compose.map

import androidx.compose.ui.input.pointer.PointerEvent

// No Android metadata here; explicit Compose pan/scale event types are routed separately.
internal actual fun hasAndroidTransformClassification(event: PointerEvent): Boolean = false
