#!/usr/bin/env python3
"""Preview or apply Pocket Wheel bindings to an explicitly selected ETS2 controls file."""

import argparse
from datetime import datetime, timezone
import difflib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile


AXES = {"j_steer": "joy.steering", "j_throttle": "joy.throttle", "j_brake": "joy.brake"}
CONSTANTS = {
    "c_jzthrottle": "0.000000", "c_jithrottle": "0.000000",
    "c_jzbrake": "0.000000", "c_jibrake": "0.000000",
    "c_throt_dz": "0.000000", "c_brake_dz": "0.000000",
}
BUTTONS = {
    "drive": "drive", "reverse": "reverse", "gear0": "neutral",
    "parkingbrake": "parkingbrake", "gearup": "shiftup", "geardown": "shiftdown",
}


def patch_controls(text):
    """Validate all required entries before changing any text; preserve all other bytes."""
    if not text.lstrip("\ufeff \t\r\n").startswith("SiiNunit"):
        raise ValueError("Expected an uncompressed SiiNunit controls file.")
    replacements = []

    def entry(kind, name):
        value = r"(?P<value>[^`\r\n]*)" if kind != "constant" else r"(?P<value>[^\"\r\n]*?)"
        delimiter = "`" if kind != "constant" else ""
        pattern = (
            r'^[ \t]*config_lines\[\d+\]:[ \t]*"' + kind + r"[ \t]+" + re.escape(name)
            + r"[ \t]+" + delimiter + value + delimiter + r'"[ \t]*(?=\r?$)'
        )
        matches = list(re.finditer(pattern, text, re.MULTILINE))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one '{kind} {name}' entry; found {len(matches)}. No changes made.")
        return matches[0]

    if entry("device", "joy").group("value").strip() != "sdk.pocketwheel":
        raise ValueError("Pocket Wheel must already be selected as the primary controller (device joy sdk.pocketwheel).")

    for kind, mapping in (("input", AXES), ("constant", CONSTANTS)):
        for name, value in mapping.items():
            match = entry(kind, name)
            replacements.append((*match.span("value"), value))
    for name, button in BUTTONS.items():
        match = entry("mix", name)
        expression = match.group("value")
        binding = f"joy.{button}?0"
        # This primary device is Pocket Wheel. Replace its prior assignment
        # (e.g. a miscaptured Park button on Reverse), keeping keyboard,
        # semantical and other-controller alternatives and expression structure.
        reference = r"(?<![\w.])joy\.[a-z0-9_]+(?:\?-?(?:\d+(?:\.\d*)?|\.\d+))?(?![\w.?])"
        value, count = re.subn(reference, lambda _: binding, expression)
        if not count:
            value = f"{expression} | {binding}" if expression.strip() else binding
        if value != expression:
            replacements.append((*match.span("value"), value))
    for start, end, value in sorted(replacements, reverse=True):
        text = text[:start] + value + text[end:]
    return text


def require_game_closed():
    result = subprocess.run(["pgrep", "-x", "eurotrucks2"], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    if result.returncode == 0:
        raise ValueError("Quit ETS2 before applying bindings; the game can overwrite its controls file while running.")
    if result.returncode != 1:
        raise ValueError("Could not verify whether ETS2 is running. No changes made.")


def apply_changes(path, original, updated):
    """Back up the exact original and atomically replace it after checking for concurrent changes."""
    require_game_closed()
    if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode):
        raise ValueError("The controls path must be a regular file, not a symbolic link.")
    if path.read_bytes() != original:
        raise ValueError("The controls file changed since it was read. Preview it again before applying.")
    if original == updated:
        return None

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    backup = path.with_name(f"{path.name}.pocketwheel-{stamp}.bak")
    # Exclusive creation avoids replacing any earlier backup.
    with backup.open("xb") as output:
        output.write(original)
        output.flush()
        os.fsync(output.fileno())
    shutil.copystat(path, backup)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=f".{path.name}.pocketwheel-", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(updated)
            output.flush()
            os.fsync(output.fileno())
        shutil.copystat(path, temporary)
        require_game_closed()
        if path.is_symlink() or path.read_bytes() != original:
            raise ValueError("The controls file changed during preparation. Original file left untouched.")
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return backup


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--controls", type=Path, required=True, help="Exact path to the intended profile's controls_osx.sii")
    parser.add_argument("--apply", action="store_true", help="Write changes after backing up the file (default: preview only)")
    args = parser.parse_args(argv)
    path = args.controls.expanduser().absolute()
    try:
        original = path.read_bytes()
        text = original.decode("utf-8")
        updated_text = patch_controls(text)
        updated = updated_text.encode("utf-8")
        if not args.apply:
            if updated == original:
                print("Pocket Wheel bindings are already configured. No changes needed.")
            else:
                print("".join(difflib.unified_diff(text.splitlines(keepends=True), updated_text.splitlines(keepends=True),
                                               fromfile=str(path), tofile=str(path) + " (proposed)")), end="")
                print("Preview only. Quit ETS2, then repeat with --apply to save these changes.")
            return
        backup = apply_changes(path, original, updated)
        if backup is None:
            print("Pocket Wheel bindings are already configured. No changes needed.")
        else:
            print(f"Updated {path}\nBackup: {backup}\nRestart ETS2 to use the bindings.")
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
