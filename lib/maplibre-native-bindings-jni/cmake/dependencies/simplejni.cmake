# SimpleJNI dependency configuration

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/vendor/SimpleJNI" EXCLUDE_FROM_ALL SYSTEM)
target_include_directories(maplibre-jni SYSTEM PRIVATE ${SIMPLEJNI_HEADERS_DIR})
target_link_libraries(maplibre-jni PRIVATE smjni::smjni)
