package org.maplibre.compose.location.desktop.linux.portal;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.types.UInt64;

public final class PortalTimestamp extends Struct {
  @Position(0)
  public final UInt64 seconds;

  @Position(1)
  public final UInt64 microseconds;

  public PortalTimestamp(UInt64 seconds, UInt64 microseconds) {
    this.seconds = seconds;
    this.microseconds = microseconds;
  }
}
