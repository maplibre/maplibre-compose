# Platform-specific linking

# macOS frameworks
if(APPLE)
    target_link_libraries(maplibre-jni PRIVATE
        "-framework Cocoa"
        "-framework QuartzCore"
    )
endif()

# X11 for Linux
if(UNIX AND NOT APPLE)
    find_package(X11 REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE ${X11_LIBRARIES})
    target_include_directories(maplibre-jni PRIVATE ${X11_INCLUDE_DIR})
endif()
