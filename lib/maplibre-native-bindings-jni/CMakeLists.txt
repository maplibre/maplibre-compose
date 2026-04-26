cmake_minimum_required(VERSION 3.21)

include(cmake/vars.cmake)

project(maplibre-jni)

include(cmake/target.cmake)

include(cmake/dependencies/platform.cmake)
include(cmake/dependencies/jni.cmake)
include(cmake/dependencies/simplejni.cmake)
include(cmake/dependencies/maplibre-native.cmake)

include(cmake/backends/metal.cmake)
include(cmake/backends/vulkan.cmake)
include(cmake/backends/opengl.cmake)
