package org.maplibre.compose.location.desktop.linux.portal;

import java.util.Map;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

@DBusInterfaceName("org.freedesktop.portal.Session")
public interface PortalSession extends DBusInterface {
  void Close();

  final class Closed extends DBusSignal {
    public final Map<String, Variant<?>> details;

    public Closed(String path, Map<String, Variant<?>> details) throws DBusException {
      super(path, details);
      this.details = details;
    }
  }
}
