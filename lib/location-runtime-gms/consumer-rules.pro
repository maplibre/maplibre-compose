# Only the ServiceLoader registration file references the backend, so R8 would
# otherwise strip it from the application.
-keep class org.maplibre.compose.gms.GmsLocationBackend {
  <init>();
}
