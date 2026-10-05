package org.maplibre.compose.map

import androidx.compose.runtime.Composable
import org.maplibre.compose.desktop.LocalComposeMapPresentationHost
import org.maplibre.compose.desktop.mapHostFactory

/** Gives Compose value-based keys reference-identity semantics for physical host resources. */
internal class ReferenceIdentityKey(private val value: Any) {
  override fun equals(other: Any?): Boolean = other is ReferenceIdentityKey && value === other.value

  override fun hashCode(): Int = System.identityHashCode(value)
}

@Composable
internal actual fun mapPresentationHostIdentity(): Any =
  ReferenceIdentityKey(LocalMlnFfiMapHostFactory.current ?: LocalComposeMapPresentationHost.current)

@Composable
internal actual fun rememberComposeMapPresentation(
  state: MapState,
  presentationOwner: MapPresentationOwnerToken,
  options: MapViewOptions,
): ComposeMapPresentation? {
  val hostFactory =
    LocalMlnFfiMapHostFactory.current ?: LocalComposeMapPresentationHost.current.mapHostFactory
  return rememberMlnFfiComposeMapPresentation(
    hostFactory = hostFactory,
    state = state,
    presentationOwner = presentationOwner,
    options = options,
  )
}
