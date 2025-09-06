# OpenGL backend configuration

if(NOT MLN_WITH_OPENGL)
    return()
endif()

target_compile_definitions(maplibre-jni PRIVATE USE_OPENGL_BACKEND)

find_package(OpenGL REQUIRED)
target_link_libraries(maplibre-jni PRIVATE OpenGL::GL)
target_include_directories(maplibre-jni PRIVATE ${OpenGL_INCLUDE_DIR})

if(UNIX AND NOT APPLE)
    find_package(OpenGL REQUIRED COMPONENTS GLX)
    target_link_libraries(maplibre-jni PRIVATE OpenGL::GLX)
endif()
