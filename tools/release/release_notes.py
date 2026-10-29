"""Read approved release notes from the release tag's message."""

from __future__ import annotations

import argparse
import pathlib
import subprocess


def tag_notes(repository: pathlib.Path, tag: str) -> str:
    """Return an annotated tag's body without its title or signature.

    Lightweight and title-only tags have no notes.
    """
    fields = subprocess.run(
        [
            "git",
            "-C",
            str(repository),
            "for-each-ref",
            "--format=%(objecttype)%00%(contents:body)",
            f"refs/tags/{tag}",
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    object_type, _, body = fields.partition("\0")
    return body.strip() if object_type == "tag" else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    args = parser.parse_args()
    print(
        tag_notes(pathlib.Path.cwd(), args.tag)
        or f"MapLibre Compose {args.tag.removeprefix('v')}."
    )


if __name__ == "__main__":
    main()
