_default:
    @just --list

pre-commit-install:
    pre-commit install

pre-commit-uninstall:
    pre-commit uninstall

pre-commit-run:
    pre-commit run --all-files

run-desktop:
    ./gradlew :demo-app:run

run-desktop-metal:
    ./gradlew :demo-app:run -PdesktopRenderer=metal

run-desktop-vulkan:
    ./gradlew :demo-app:run -PdesktopRenderer=vulkan

run-desktop-opengl:
    ./gradlew :demo-app:run -PdesktopRenderer=opengl
