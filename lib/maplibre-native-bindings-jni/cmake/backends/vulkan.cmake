# Vulkan backend configuration

if(NOT MLN_WITH_VULKAN)
    return()
endif()

target_compile_definitions(maplibre-jni PRIVATE USE_VULKAN_BACKEND)

if(APPLE)
    target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_METAL_EXT)
elseif(UNIX)
    target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_XLIB_KHR)
elseif(WIN32)
    target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_WIN32_KHR)
endif()

find_package(Vulkan REQUIRED)
target_link_libraries(maplibre-jni PRIVATE Vulkan::Vulkan)

# Don't use system Vulkan headers - use MapLibre's vendored headers for compatibility
# The vendored headers are included via the Mapbox::Map target
