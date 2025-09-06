# Metal backend configuration

if(NOT MLN_WITH_METAL)
    return()
endif()

if(NOT APPLE)
    message(FATAL_ERROR "Metal backend is only supported on macOS")
endif()

target_compile_definitions(maplibre-jni PRIVATE USE_METAL_BACKEND)
target_link_libraries(maplibre-jni PRIVATE "-framework Metal")
