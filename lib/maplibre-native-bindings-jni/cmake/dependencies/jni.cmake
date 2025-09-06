# JNI system dependency

find_package(JNI REQUIRED)
target_include_directories(maplibre-jni SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(maplibre-jni PRIVATE ${JNI_LIBRARIES})
