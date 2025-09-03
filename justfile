set windows-shell := ["powershell.exe", "-c"]

_default:
    @just --list

pre-commit-install:
    pre-commit install

pre-commit-uninstall:
    pre-commit uninstall

format:
    pre-commit run --all-files

clean-vcpkg:
    cd lib/maplibre-native-bindings-jni/vendor/vcpkg; git reset --hard; git clean -fdx

clean-gradle:
    ./gradlew clean

clean-cmake:
    rm -rf lib/maplibre-native-bindings-jni/build/cmake

clean: clean-gradle clean-vcpkg clean-cmake

run-desktop:
    ./gradlew :demo-app:run

run-desktop-metal:
    ./gradlew :demo-app:run -PdesktopRenderer=metal

run-desktop-vulkan:
    ./gradlew :demo-app:run -PdesktopRenderer=vulkan

run-desktop-opengl:
    ./gradlew :demo-app:run -PdesktopRenderer=opengl
