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

target_include_directories(maplibre-jni SYSTEM PRIVATE ${maplibre-native_SOURCE_DIR}/src)

# Windows loads Vulkan dynamically through Vulkan-Hpp, and MapLibre provides the
# compatible vendored headers via Mapbox::Map. Requiring a system Vulkan SDK on
# Windows breaks CI because GitHub runners do not ship one.
if(NOT WIN32)
    find_package(Vulkan REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE Vulkan::Vulkan)
endif()
