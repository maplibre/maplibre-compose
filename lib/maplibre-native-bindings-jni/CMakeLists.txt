cmake_minimum_required(VERSION 3.21)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Set MSVC runtime library to dynamic (MD/MDd)
if(WIN32)
    cmake_policy(SET CMP0091 NEW)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
endif()

set(SIMPLEJNI_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/build/generated/simplejni-headers" CACHE PATH "Path to SimpleJNI generated headers")

if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR is not defined")
endif()

if(APPLE)
    enable_language(OBJCXX)
endif()

# Declare project with vendored dependencies

set(SimpleJNI_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/SimpleJNI")
set(maplibre-native_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/maplibre-native")
if(WIN32)
    set(CMAKE_TOOLCHAIN_FILE ${maplibre-native_SOURCE_DIR}/platform/windows/custom-toolchain.cmake)
endif()
project(maplibre-jni)

# FetchContent causes problems with the vcpkg toolchain, see (TODO link)
# So we use git submodules instead and add_subdirectory() AFTER project()
add_subdirectory(${SimpleJNI_SOURCE_DIR} EXCLUDE_FROM_ALL SYSTEM)
add_subdirectory(${maplibre-native_SOURCE_DIR} EXCLUDE_FROM_ALL SYSTEM)

find_package(JNI REQUIRED)

# Create our JNI library
add_library(maplibre-jni SHARED
    src/main/cpp/maplibre_map.cpp
    src/main/cpp/conversions.cpp
    src/main/cpp/jni_on_load.cpp
    src/main/cpp/jni_map_observer.cpp
    src/main/cpp/canvas_frontend.cpp
    src/main/cpp/canvas_metal_backend.mm
    src/main/cpp/canvas_opengl_backend.cpp
)

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
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/arm64-windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/x64-windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/x86-windows/include
    )
endif()

# Add Metal-cpp headers for macOS
if(APPLE)
    target_include_directories(maplibre-jni SYSTEM PRIVATE
        ${maplibre-native_SOURCE_DIR}/vendor/metal-cpp
    )
endif()

# Compile options
target_compile_options(maplibre-jni PRIVATE
    $<$<CXX_COMPILER_ID:GNU>:-frtti -fexceptions>
    $<$<CXX_COMPILER_ID:Clang>:-frtti -fexceptions>
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
    if(APPLE)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_METAL_EXT)
    elseif(UNIX)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_XLIB_KHR)
    elseif(WIN32)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_WIN32_KHR)
    endif()
endif()
if(MLN_WITH_OPENGL)
    target_compile_definitions(maplibre-jni PRIVATE USE_OPENGL_BACKEND)
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
    if(UNIX AND NOT APPLE)
        find_package(OpenGL REQUIRED COMPONENTS GLX)
        target_link_libraries(maplibre-jni PRIVATE OpenGL::GLX)
    endif()
endif()

# Set output directory
set_target_properties(maplibre-jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    RUNTIME_OUTPUT_DIRECTORY ${OUTPUT_DIR}
)
