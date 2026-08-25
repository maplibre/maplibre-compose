package org.maplibre.compose.location.desktop.linux.portal;

import java.util.Map;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.DBusMemberName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

@DBusInterfaceName("org.freedesktop.portal.Location")
public interface LocationPortal extends DBusInterface {
  @DBusMemberName("CreateSession")
  DBusPath createSession(Map<String, Variant<?>> options);

  @DBusMemberName("Start")
  DBusPath start(DBusPath sessionHandle, String parentWindow, Map<String, Variant<?>> options);

  final class LocationUpdated extends DBusSignal {
    public final DBusPath sessionHandle;
    public final Map<String, Variant<?>> location;

    public LocationUpdated(
        String path, DBusPath sessionHandle, Map<String, Variant<?>> location)
        throws DBusException {
      super(path, sessionHandle, location);
      this.sessionHandle = sessionHandle;
      this.location = location;
    }
  }
}
