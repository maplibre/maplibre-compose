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
