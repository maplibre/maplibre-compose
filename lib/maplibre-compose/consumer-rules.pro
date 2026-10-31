# The Vulkan and OpenGL bridges reach their JNI natives by symbol name.
-keep class org.maplibre.compose.mlnffi.AndroidVulkanNativeBridge { *; }
-keep class org.maplibre.compose.mlnffi.AndroidEglNativeBridge { *; }
