cmake_minimum_required(VERSION 3.21)
project(maplibre-jni)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SIMPLEJNI_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/build/generated/simplejni-headers" CACHE PATH "Path to SimpleJNI generated headers")

if(APPLE)
    enable_language(OBJCXX)
endif()

find_package(JNI REQUIRED)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Fetch MapLibre Native using FetchContent
include(FetchContent)

FetchContent_Declare(
    maplibre-native
    GIT_REPOSITORY https://github.com/maplibre/maplibre-native.git
    GIT_TAG        core-fe158c7e9b0b3f748f88d34ad384a7bcbc2cf903
    GIT_SHALLOW    TRUE
    GIT_SUBMODULES_RECURSE TRUE
    GIT_PROGRESS   TRUE
)

# Fetch SimpleJNI library
FetchContent_Declare(
    SimpleJNI
    GIT_REPOSITORY https://github.com/gershnik/SimpleJNI.git
    GIT_TAG        3.14
    GIT_SHALLOW    TRUE
)

# Make MapLibre Native and SimpleJNI available
FetchContent_MakeAvailable(maplibre-native SimpleJNI)

# Create our JNI library
set(MAPLIBRE_JNI_SOURCES
    src/main/cpp/maplibre_map.cpp
    src/main/cpp/conversions.cpp
    src/main/cpp/jni_on_load.cpp
    src/main/cpp/jni_map_observer.cpp
    src/main/cpp/canvas_frontend.cpp
    src/main/cpp/canvas_backend.cpp
)

if(MLN_WITH_METAL)
    list(APPEND MAPLIBRE_JNI_SOURCES
        src/main/cpp/canvas_metal_backend.mm
    )
# elseif(MLN_WITH_VULKAN)
#     list(APPEND MAPLIBRE_JNI_SOURCES
#         src/main/cpp/canvas_vulkan_backend.cpp
#     )
#     if(APPLE)
#         list(APPEND MAPLIBRE_JNI_SOURCES
#             src/main/cpp/canvas_vulkan_backend.mm
#         )
#     endif()
# elseif(MLN_WITH_OPENGL)
#     list(APPEND MAPLIBRE_JNI_SOURCES
#         src/main/cpp/canvas_gl_backend.cpp
#     )
#     if(MLN_WITH_EGL)
#         list(APPEND MAPLIBRE_JNI_SOURCES
#             src/main/cpp/canvas_egl_strategy.cpp
#         )
#     elseif(WIN32)
#         list(APPEND MAPLIBRE_JNI_SOURCES
#             src/main/cpp/canvas_wgl_strategy.cpp
#         )
#     elseif(UNIX AND NOT APPLE)
#         # Linux with GLX (desktop OpenGL)
#         list(APPEND MAPLIBRE_JNI_SOURCES
#             src/main/cpp/canvas_glx_strategy.cpp
#         )
#     endif()
else()
    message(FATAL_ERROR "Unsupported backend configuration.")
endif()

add_library(maplibre-jni SHARED ${MAPLIBRE_JNI_SOURCES})

# Include directories
target_include_directories(maplibre-jni
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)
target_include_directories(maplibre-jni
    SYSTEM PRIVATE
        ${SIMPLEJNI_HEADERS_DIR}
        ${JNI_INCLUDE_DIRS}
        ${maplibre-native_SOURCE_DIR}/include
        ${maplibre-native_SOURCE_DIR}/platform/default/include
        ${maplibre-native_SOURCE_DIR}/src
)

# Add Windows-specific includes
if(WIN32)
    target_include_directories(maplibre-jni SYSTEM PRIVATE
        ${maplibre-native_SOURCE_DIR}/platform/windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/x64-windows/include
    )
endif()

# Add Metal-cpp headers for macOS
if(MLN_WITH_METAL)
    target_include_directories(maplibre-jni SYSTEM PRIVATE
        ${maplibre-native_SOURCE_DIR}/vendor/metal-cpp
    )
endif()

# Compile options (will inherit from mbgl-compiler-options)
target_compile_options(maplibre-jni PRIVATE
    -frtti
    -fexceptions
)

# Windows-specific definitions
if(WIN32)
    target_compile_definitions(maplibre-jni PRIVATE
        NOMINMAX  # Prevent min/max macro conflicts
    )
endif()

# Pass backend selection to C++ code
if(MLN_WITH_METAL)
    target_compile_definitions(maplibre-jni PRIVATE USE_METAL_BACKEND)
endif()
if(MLN_WITH_VULKAN)
    target_compile_definitions(maplibre-jni PRIVATE USE_VULKAN_BACKEND)
endif()
if(MLN_WITH_OPENGL)
    if (MLN_WITH_EGL)
        target_compile_definitions(maplibre-jni PRIVATE USE_EGL_BACKEND)
    elseif(WIN32)
        target_compile_definitions(maplibre-jni PRIVATE USE_WGL_BACKEND)
    elseif(UNIX AND NOT APPLE)
        target_compile_definitions(maplibre-jni PRIVATE USE_GLX_BACKEND)
    endif()
endif()

# Link common libraries
target_link_libraries(maplibre-jni PRIVATE
    Mapbox::Map
    mbgl-compiler-options
    mbgl-vendor-unique_resource
    smjni::smjni
    ${JNI_LIBRARIES}
)

# Platform-specific linking
if(APPLE)
    target_link_libraries(maplibre-jni PRIVATE
        "-framework Cocoa"
        "-framework QuartzCore"
    )
endif()

if (MLN_WITH_METAL)
    target_link_libraries(maplibre-jni PRIVATE "-framework Metal")
endif()

if(UNIX AND NOT APPLE)
    find_package(X11 REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE ${X11_LIBRARIES})
    target_include_directories(maplibre-jni PRIVATE ${X11_INCLUDE_DIR})
endif()

if(MLN_WITH_VULKAN)
    # Find Vulkan library
    find_package(Vulkan REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE Vulkan::Vulkan)
    # Don't use system Vulkan headers - use MapLibre's vendored headers for compatibility
    # The vendored headers are included via the Mapbox::Map target
endif()

if(MLN_WITH_OPENGL AND MLN_WITH_EGL)
    if(WIN32)
        # On Windows, use ANGLE libraries from vcpkg
        find_package(unofficial-angle CONFIG REQUIRED)
        target_link_libraries(maplibre-jni PRIVATE unofficial::angle::libEGL unofficial::angle::libGLESv2)
    else()
        # On Linux, use system EGL
        find_library(EGL_LIBRARY EGL)
        if(NOT EGL_LIBRARY)
            message(FATAL_ERROR "EGL library not found")
        endif()
        target_link_libraries(maplibre-jni PRIVATE ${EGL_LIBRARY})
    endif()
elseif(MLN_WITH_OPENGL)
    find_package(OpenGL REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE OpenGL::GL)
    target_include_directories(maplibre-jni PRIVATE ${OpenGL_INCLUDE_DIR})

    # Link GLX on Linux when not using EGL
    if(UNIX AND NOT APPLE AND NOT MLN_WITH_EGL)
        find_package(OpenGL REQUIRED COMPONENTS GLX)
        target_link_libraries(maplibre-jni PRIVATE OpenGL::GLX)
    endif()
endif()

# Set output directory
set_target_properties(maplibre-jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/build/lib/main/shared
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/build/lib/main/shared
)
