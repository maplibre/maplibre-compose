# MapLibre Native dependency configuration

include(FetchContent)

set(MAPLIBRE_NATIVE_VERSION core-9b6325a14e2cf1cc29ab28c1855ad376f1ba4903)
set(MAPLIBRE_NATIVE_BASE_URL https://github.com/maplibre/maplibre-native/releases/download/${MAPLIBRE_NATIVE_VERSION})
set(MAPLIBRE_NATIVE_HEADERS_SHA256 56354473ff88653046f62c4ffe2ee879e97eee0cb7f8385210e8b650322a78b7)

# TODO: detect set arch and renderer properly
if(WIN32)
    # TODO: need amalgam version or libraries
    set(MAPLIBRE_NATIVE_LIBRARY_NAME maplibre-native-core-windows-x64-opengl.lib)
    set(MAPLIBRE_NATIVE_LIBRARY_SHA256 543cd81afc4ed32fd3ed8c813de557a9730e51ba5943d7f4cab20adef5a114fa)
elseif(APPLE)
    set(MAPLIBRE_NATIVE_LIBRARY_NAME libmaplibre-native-core-amalgam-macos-arm64-metal.a)
    set(MAPLIBRE_NATIVE_LIBRARY_SHA256 127707ffa405f3cbc78741f7f21d44b35c3b618a30c16cd0a3881331b9d4c765)
elseif(LINUX)
    set(MAPLIBRE_NATIVE_LIBRARY_NAME libmaplibre-native-core-amalgam-linux-x64-opengl.a)
    set(MAPLIBRE_NATIVE_LIBRARY_SHA256 25ec73c7ba7b198dd0dc0ce7959ee4096e8b3dac68fd55bb4fb94be91ceb3421)
endif()

FetchContent_Populate(maplibre-native-lib
    URL ${MAPLIBRE_NATIVE_BASE_URL}/${MAPLIBRE_NATIVE_LIBRARY_NAME}
    URL_HASH SHA256=${MAPLIBRE_NATIVE_LIBRARY_SHA256}
    DOWNLOAD_NO_EXTRACT TRUE
)

FetchContent_Populate(maplibre-native-headers
    URL ${MAPLIBRE_NATIVE_BASE_URL}/maplibre-native-headers.tar.gz
    URL_HASH SHA256=${MAPLIBRE_NATIVE_HEADERS_SHA256}
)

target_include_directories(maplibre-jni SYSTEM PRIVATE
    ${maplibre-native-headers_SOURCE_DIR}/include
    ${maplibre-native-headers_SOURCE_DIR}/vendor/metal-cpp
    ${maplibre-native-headers_SOURCE_DIR}/vendor/maplibre-native-base/include
    ${maplibre-native-headers_SOURCE_DIR}/vendor/maplibre-native-base/extras/expected-lite/include
    ${maplibre-native-headers_SOURCE_DIR}/vendor/maplibre-native-base/deps/geojson.hpp/include
    ${maplibre-native-headers_SOURCE_DIR}/vendor/maplibre-native-base/deps/geometry.hpp/include
    ${maplibre-native-headers_SOURCE_DIR}/vendor/maplibre-native-base/deps/variant/include
)

target_link_libraries(maplibre-jni PRIVATE
    ${maplibre-native-lib_SOURCE_DIR}/${MAPLIBRE_NATIVE_LIBRARY_NAME}
)

target_compile_definitions(maplibre-jni PRIVATE
    M_PI=3.14159265358979323846
)
