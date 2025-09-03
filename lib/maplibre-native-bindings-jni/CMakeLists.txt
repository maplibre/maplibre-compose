cmake_minimum_required(VERSION 3.21)

if(NOT DEFINED SIMPLEJNI_HEADERS_DIR)
    message(FATAL_ERROR "SIMPLEJNI_HEADERS_DIR is not defined")
endif()

if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR is not defined")
endif()

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(WIN32)
    cmake_policy(SET CMP0091 NEW)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
    set(CMAKE_TOOLCHAIN_FILE ${CMAKE_CURRENT_SOURCE_DIR}/vendor/vcpkg/scripts/buildsystems/vcpkg.cmake)
endif()

project(maplibre-jni)

if(APPLE)
    enable_language(OBJCXX)
endif()

# Fetch dependencies

include(FetchContent)

FetchContent_Declare(
  maplibre-native
  GIT_REPOSITORY https://github.com/maplibre/maplibre-native.git
  GIT_TAG core-9b6325a14e2cf1cc29ab28c1855ad376f1ba4903
  GIT_SHALLOW 1
  GIT_CONFIG core.longpaths=true
  SYSTEM
  EXCLUDE_FROM_ALL
)

FetchContent_Declare(
  SimpleJNI
  GIT_REPOSITORY https://github.com/gershnik/SimpleJNI.git
  GIT_TAG 3.14
  GIT_SHALLOW 1
  SYSTEM
  EXCLUDE_FROM_ALL
)

FetchContent_MakeAvailable(SimpleJNI)
FetchContent_MakeAvailable(maplibre-native)

# Create our library

add_library(maplibre-jni SHARED
    src/main/cpp/maplibre_map.cpp
    src/main/cpp/conversions.cpp
    src/main/cpp/jni_on_load.cpp
    src/main/cpp/jni_map_observer.cpp
    src/main/cpp/canvas_frontend.cpp
    src/main/cpp/canvas_metal_backend.mm
    src/main/cpp/canvas_opengl_backend.cpp
)

target_include_directories(maplibre-jni
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp
)

target_compile_options(maplibre-jni PRIVATE
    $<$<CXX_COMPILER_ID:GNU>:-frtti -fexceptions>
    $<$<CXX_COMPILER_ID:Clang>:-frtti -fexceptions>
    # no extra options on MSVC
)

set_target_properties(maplibre-jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${OUTPUT_DIR}
    RUNTIME_OUTPUT_DIRECTORY ${OUTPUT_DIR}
)

# Add JNI

find_package(JNI REQUIRED)
target_include_directories(maplibre-jni SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(maplibre-jni PRIVATE ${JNI_LIBRARIES})

# Add SimpleJNI

target_include_directories(maplibre-jni SYSTEM PRIVATE ${SIMPLEJNI_HEADERS_DIR})
target_link_libraries(maplibre-jni PRIVATE smjni::smjni)

# Add MapLibre Native

if(MLN_WITH_METAL)
    target_compile_definitions(maplibre-jni PRIVATE USE_METAL_BACKEND)
elseif(MLN_WITH_VULKAN)
    target_compile_definitions(maplibre-jni PRIVATE USE_VULKAN_BACKEND)
    if(APPLE)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_METAL_EXT)
    elseif(UNIX)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_XLIB_KHR)
    elseif(WIN32)
        target_compile_definitions(maplibre-jni PRIVATE VK_USE_PLATFORM_WIN32_KHR)
    endif()
elseif(MLN_WITH_OPENGL)
    target_compile_definitions(maplibre-jni PRIVATE USE_OPENGL_BACKEND)
endif()

target_include_directories(maplibre-jni SYSTEM PRIVATE
    ${maplibre-native_SOURCE_DIR}/include
    ${maplibre-native_SOURCE_DIR}/platform/default/include
    ${maplibre-native_SOURCE_DIR}/src
)

target_link_libraries(maplibre-jni PRIVATE
    Mapbox::Map
)

if(WIN32)
    target_include_directories(maplibre-jni SYSTEM PRIVATE
        ${maplibre-native_SOURCE_DIR}/platform/windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/arm64-windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/x64-windows/include
        ${maplibre-native_SOURCE_DIR}/platform/windows/vendor/vcpkg/installed/x86-windows/include
    )
    target_compile_definitions(maplibre-jni PRIVATE
        NOMINMAX  # Prevent min/max macro conflicts with maplibre-native
    )
elseif(APPLE)
    target_include_directories(maplibre-jni SYSTEM PRIVATE
        ${maplibre-native_SOURCE_DIR}/vendor/metal-cpp
    )
endif()

# Add Apple frameworks

if(APPLE)
    target_link_libraries(maplibre-jni PRIVATE
        "-framework Cocoa"
        "-framework QuartzCore"
    )
    if (MLN_WITH_METAL)
        target_link_libraries(maplibre-jni PRIVATE "-framework Metal")
    endif()
endif()

# Add X11

if(UNIX AND NOT APPLE)
    find_package(X11 REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE ${X11_LIBRARIES})
    target_include_directories(maplibre-jni PRIVATE ${X11_INCLUDE_DIR})
endif()

# Add Vulkan

if(MLN_WITH_VULKAN)
    find_package(Vulkan REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE Vulkan::Vulkan)
endif()

# Add OpenGL

if(MLN_WITH_OPENGL)
    find_package(OpenGL REQUIRED)
    target_link_libraries(maplibre-jni PRIVATE OpenGL::GL)
    target_include_directories(maplibre-jni PRIVATE ${OpenGL_INCLUDE_DIR})

    if(UNIX AND NOT APPLE)
        find_package(OpenGL REQUIRED COMPONENTS GLX)
        target_link_libraries(maplibre-jni PRIVATE OpenGL::GLX)
    endif()
endif()
