cmake_minimum_required(VERSION 3.22.1)

project(maplibre_compose_opengl LANGUAGES CXX)

add_library(maplibre_compose_opengl SHARED hardware_buffer_image.cpp)

target_compile_features(maplibre_compose_opengl PRIVATE cxx_std_20)
target_compile_definitions(
  maplibre_compose_opengl
  # Hardware buffer import runs only on API levels that provide it.
  PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(maplibre_compose_opengl PRIVATE
                       -Werror=unguarded-availability)

target_link_libraries(maplibre_compose_opengl PRIVATE android EGL GLESv2)
