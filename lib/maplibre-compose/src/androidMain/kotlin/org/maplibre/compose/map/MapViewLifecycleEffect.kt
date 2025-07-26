package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView
import org.maplibre.compose.style.SafeStyle

@Composable
internal fun MapViewLifecycleEffect(mapView: MapView?, rememberedStyle: SafeStyle?) {
  if (mapView == null) return
  val observer =
    remember(mapView, rememberedStyle) { MapViewLifecycleObserver(mapView, rememberedStyle) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, observer) {
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
}
