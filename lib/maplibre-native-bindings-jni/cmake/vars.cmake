set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED OUTPUT_DIR)
    message(FATAL_ERROR "OUTPUT_DIR is not defined")
endif()

set(SIMPLEJNI_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/build/generated/simplejni-headers" CACHE PATH "Path to SimpleJNI generated headers")

if(WIN32)
    cmake_policy(SET CMP0091 NEW)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
endif()

if(APPLE)
    enable_language(OBJCXX)
endif()
