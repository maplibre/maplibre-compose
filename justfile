set windows-shell := ["powershell.exe", "-c"]

_default:
    @just --list

# Reset the vcpkg submodule to a clean state
clean-vcpkg:
    cd lib/maplibre-native-bindings-jni/vendor/maplibre-native/platform/windows/vendor/vcpkg; git reset --hard; git clean -fdx

run-js:
    ./gradlew :demo-app:jsBrowserDevelopmentRun

run-desktop:
    ./gradlew :demo-app:run

run-desktop-metal:
    ./gradlew :demo-app:run -PdesktopRenderer=metal

run-desktop-vulkan:
    ./gradlew :demo-app:run -PdesktopRenderer=vulkan

run-desktop-opengl:
    ./gradlew :demo-app:run -PdesktopRenderer=opengl
