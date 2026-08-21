package org.maplibre.compose.location.desktop.linux.portal;

import java.util.Map;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

@DBusInterfaceName("org.freedesktop.portal.Request")
public interface PortalRequest extends DBusInterface {
  void Close();

  final class Response extends DBusSignal {
    public final UInt32 response;
    public final Map<String, Variant<?>> results;

    public Response(String path, UInt32 response, Map<String, Variant<?>> results)
        throws DBusException {
      super(path, response, results);
      this.response = response;
      this.results = results;
    }
  }
}
