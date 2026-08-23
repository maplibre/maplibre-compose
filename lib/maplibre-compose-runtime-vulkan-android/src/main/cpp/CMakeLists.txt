cmake_minimum_required(VERSION 3.22.1)

project(maplibre_compose_vulkan LANGUAGES CXX)

add_library(maplibre_compose_vulkan SHARED vulkan_context.cpp)

target_compile_features(maplibre_compose_vulkan PRIVATE cxx_std_20)
target_compile_definitions(
  maplibre_compose_vulkan
  PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)

target_link_libraries(maplibre_compose_vulkan PRIVATE android log vulkan)
