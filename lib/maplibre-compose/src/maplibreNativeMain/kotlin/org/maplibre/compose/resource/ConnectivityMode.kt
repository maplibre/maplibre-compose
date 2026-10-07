package org.maplibre.compose.resource

/**
 * Chooses the connectivity that MapLibre Native uses.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
public sealed interface ConnectivityMode {
  /** Uses operating-system connectivity where it is monitored, and permits requests otherwise. */
  public data object Automatic : ConnectivityMode

  /** Permits network requests regardless of operating-system connectivity. */
  public data object ForceOnline : ConnectivityMode

  /** Forces offline connectivity; maps can use offline packs and usable cached resources. */
  public data object ForceOffline : ConnectivityMode
}

/** Keeps caller matches open. The library never uses this mode. */
internal data object UnspecifiedConnectivityMode : ConnectivityMode
