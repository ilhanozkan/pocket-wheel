#!/usr/bin/env python3
"""Install only Pocket Wheel; preserve previous builds and all game profiles."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_STEAM = Path.home() / "Library/Application Support/Steam"

def find_game():
    libraries = [DEFAULT_STEAM]
    folders = DEFAULT_STEAM / "steamapps/libraryfolders.vdf"
    if folders.exists():
        libraries += [Path(p.replace("\\\\", "\\")) for p in re.findall(r'"path"\s+"([^"]+)"', folders.read_text())]
    for library in libraries:
        game = library / "steamapps/common/Euro Truck Simulator 2/Euro Truck Simulator 2.app"
        if game.exists():
            return game
    return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game", type=Path, help="Path to Euro Truck Simulator 2.app")
    parser.add_argument("--uninstall", action="store_true")
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()
    game = args.game or find_game()
    if not game:
        parser.error("ETS2 was not found. Mount its Steam library or pass --game '/path/Euro Truck Simulator 2.app'.")
    binary = game / "Contents/MacOS/eurotrucks2"
    if not binary.is_file():
        parser.error("The selected app has no Contents/MacOS/eurotrucks2 executable.")
    architecture = subprocess.check_output(["file", str(binary)], text=True).strip()
    destination = binary.parent / "plugins/pocketwheel.dylib"
    if args.inspect:
        print(json.dumps({"game": str(game), "architecture": architecture, "plugin": str(destination), "installed": destination.exists()}, indent=2))
        return
    if subprocess.run(["pgrep", "-x", "eurotrucks2"], stdout=subprocess.DEVNULL).returncode == 0:
        parser.error("Quit ETS2 before installing or removing its plugin.")
    if "x86_64" not in architecture:
        parser.error("This build targets Intel/Rosetta ETS2. The installed game has a different architecture.")
    source = ROOT / "dist/pocketwheel.dylib"
    receipt = ROOT / ".cache/plugin-install.json"
    if args.uninstall:
        if not destination.exists():
            print("Pocket Wheel is already removed.")
            return
        if not receipt.exists():
            parser.error("No installation receipt. Remove only pocketwheel.dylib manually after checking its path.")
        record = json.loads(receipt.read_text())
        if record["path"] != str(destination) or hashlib.sha256(destination.read_bytes()).hexdigest() != record["sha256"]:
            parser.error("Installed file has changed since this installer wrote it; refusing to remove it.")
        destination.unlink()
        print(f"Removed {destination}. Existing control bindings and profiles were preserved.")
        return
    if not source.is_file():
        parser.error("Build the plugin first: bash scripts/build-plugin.sh")
    if destination.exists() and destination.read_bytes() != source.read_bytes():
        backup = ROOT / ".cache/plugin-backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        backup.mkdir(parents=True)
        shutil.copy2(destination, backup / "pocketwheel.dylib")
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    except PermissionError:
        parser.exit(1, f"macOS blocked changes to the installed game's app bundle.\n"
                       f"Use Finder to create this folder and copy the built plugin into it:\n{destination.parent}\n"
                       f"Plugin to copy: {source}\n"
                       "Alternatively, run this installer from a terminal app allowed to manage installed apps. "
                       "No security setting is changed by this script.\n")
    receipt.parent.mkdir(parents=True, exist_ok=True)
    receipt.write_text(json.dumps({"path": str(destination), "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}, indent=2) + "\n")
    print(f"Installed {destination}\nRestart ETS2 to load Pocket Wheel.")

if __name__ == "__main__":
    main()
