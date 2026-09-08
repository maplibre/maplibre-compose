package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.maplibre.compose.desktop.LocalComposeMapPresentationHost
import org.maplibre.compose.desktop.bridge.ComposeMapPresentationHostFactory

/** Gives Compose value-based keys reference-identity semantics for physical host resources. */
internal class ReferenceIdentityKey(private val value: Any) {
  override fun equals(other: Any?): Boolean = other is ReferenceIdentityKey && value === other.value

  override fun hashCode(): Int = System.identityHashCode(value)
}

@Composable
internal actual fun mapPresentationHostIdentity(): Any =
  ReferenceIdentityKey(LocalMlnFfiMapHostFactory.current ?: LocalComposeMapPresentationHost.current)

@Composable
internal actual fun ComposableMapView(
  modifier: Modifier,
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
) {
  val hostFactory =
    LocalMlnFfiMapHostFactory.current
      ?: LocalComposeMapPresentationHost.current.let { presentationHost ->
        remember(ReferenceIdentityKey(presentationHost)) {
          ComposeMapPresentationHostFactory(presentationHost)
        }
      }
  MlnFfiMapView(
    hostFactory = hostFactory,
    modifier = modifier,
    state = state,
    presentationOwner = presentationOwner,
    options = options,
  )
}
