_default:
    @just --list

format:
    ./scripts/format

hooks-install:
    ./scripts/git-hooks install

hooks-uninstall:
    ./scripts/git-hooks uninstall

run-desktop:
    ./gradlew :demo-app:run

# TODO: flesh this out
