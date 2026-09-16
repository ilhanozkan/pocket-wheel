"""Regression tests for the profile patcher; every write uses a temporary directory."""

import contextlib
import importlib.util
import io
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/configure-ets2.py"
SPEC = importlib.util.spec_from_file_location("configure_ets2", SCRIPT)
config = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(config)


# Sanitized entries from the macOS profile. Unrelated controls intentionally remain.
ENTRIES = [
    "device keyboard `sys.keyboard`", "device joy `sdk.pocketwheel`",
    "input j_steer `joy.steering`", "input j_throttle `joy.y`", "input j_brake `joy.y`",
    "constant c_jzthrottle 1.000000", "constant c_jithrottle 1.000000",
    "constant c_jzbrake 1.000000", "constant c_jibrake 0.000000",
    "constant c_throt_dz 0.100000", "constant c_brake_dz 0.100000",
    "constant c_steer_dz 0.000000",
    "mix drive `semantical.drive?0`", "mix reverse `semantical.reverse?0`",
    "mix gear0 `semantical.gear0?0`",
    "mix parkingbrake `keyboard.space?0 | semantical.parkingbrake?0`",
    "mix gearup `keyboard.lshift?0 | keyboard.rshift?0 | semantical.gearup?0`",
    "mix geardown `keyboard.lctrl?0 | keyboard.rctrl?0 | semantical.geardown?0`",
    "mix horn `keyboard.h?0 | semantical.horn?0`",
    "mix gear1 `joy.b15?0 | semantical.gear1?0`",
]
FIXTURE = "SiiNunit\n{\ninput_config : _nameless.test {\n version: 32\n config_lines: 20\n" + "".join(
    f' config_lines[{index}]: "{entry}"\n' for index, entry in enumerate(ENTRIES)
) + "}\n}\n"


class PatchTests(unittest.TestCase):
    def test_repairs_pedals_and_keeps_existing_alternatives(self):
        result = config.patch_controls(FIXTURE)
        self.assertIn("input j_throttle `joy.throttle`", result)
        self.assertIn("input j_brake `joy.brake`", result)
        self.assertIn("constant c_jithrottle 0.000000", result)
        self.assertIn("constant c_jzthrottle 0.000000", result)
        self.assertIn("constant c_jzbrake 0.000000", result)
        self.assertIn("constant c_throt_dz 0.000000", result)
        self.assertIn("constant c_brake_dz 0.000000", result)
        for name, button in config.BUTTONS.items():
            original = next(entry for entry in ENTRIES if entry.startswith(f"mix {name} "))
            self.assertIn(original[:-1] + f" | joy.{button}?0`", result)
        for original in (ENTRIES[0], ENTRIES[11], ENTRIES[18], ENTRIES[19]):
            self.assertIn(original, result)
        # No line numbers, count, unrelated lines, or ordering are changed.
        self.assertEqual(len(result.splitlines()), len(FIXTURE.splitlines()))
        changed = [a for a, b in zip(FIXTURE.splitlines(), result.splitlines()) if a != b]
        self.assertEqual(len(changed), 13)

    def test_replaces_wrong_primary_joy_binding_and_preserves_alternatives(self):
        original = FIXTURE.replace(
            "mix reverse `semantical.reverse?0`",
            "mix reverse `joy.parkingbrake?0 | keyboard.r?0 | joy2.b3?0 | semantical.reverse?0`",
        )
        result = config.patch_controls(original)
        self.assertIn("mix reverse `joy.reverse?0 | keyboard.r?0 | joy2.b3?0 | semantical.reverse?0`", result)
        reverse = next(line for line in result.splitlines() if '"mix reverse ' in line)
        self.assertNotIn("joy.parkingbrake", reverse)
        self.assertEqual(config.patch_controls(result), result)

    def test_idempotent_and_preserves_line_endings(self):
        for original in (FIXTURE, "\ufeff" + FIXTURE.replace("\n", "\r\n")):
            result = config.patch_controls(original)
            self.assertEqual(config.patch_controls(result), result)
            self.assertEqual(result.count("\r\n"), original.count("\r\n"))
            self.assertEqual(result.startswith("\ufeff"), original.startswith("\ufeff"))

    def test_missing_or_duplicate_required_entry_is_rejected(self):
        for kind, names in (("input", config.AXES), ("constant", config.CONSTANTS), ("mix", config.BUTTONS)):
            for name in names:
                with self.subTest(kind=kind, name=name):
                    line = next(line for line in FIXTURE.splitlines(keepends=True) if f'"{kind} {name} ' in line)
                    with self.assertRaises(ValueError):
                        config.patch_controls(FIXTURE.replace(line, ""))
                    with self.assertRaises(ValueError):
                        config.patch_controls(FIXTURE + line)

    def test_other_selected_controller_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "already be selected"):
            config.patch_controls(FIXTURE.replace("sdk.pocketwheel", "sdk.other"))


class FileTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "controls_osx.sii"
        self.path.write_bytes(FIXTURE.encode())
        self.path.chmod(0o640)

    def run_main(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            config.main(["--controls", str(self.path), *args])
        return stdout.getvalue()

    def test_default_dry_run_does_not_write_or_check_process(self):
        with mock.patch.object(config, "require_game_closed") as check:
            self.assertIn("Preview only", self.run_main())
        check.assert_not_called()
        self.assertEqual(self.path.read_text(), FIXTURE)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_apply_backs_up_exact_original_and_preserves_permissions(self):
        with mock.patch.object(config, "require_game_closed"):
            self.run_main("--apply")
            self.run_main("--apply")
        backups = list(self.path.parent.glob("*.bak"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), FIXTURE.encode())
        self.assertEqual(self.path.read_text(), config.patch_controls(FIXTURE))
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o640)
        self.assertEqual(stat.S_IMODE(backups[0].stat().st_mode), 0o640)
        self.assertEqual(len(list(self.path.parent.iterdir())), 2)

    def test_missing_required_line_refuses_without_backup_or_write(self):
        original = FIXTURE.replace("input j_brake ", "input removed_brake ")
        self.path.write_text(original)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.run_main("--apply")
        self.assertEqual(self.path.read_text(), original)
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_running_game_or_process_check_error_refuses(self):
        for returncode in (0, 2):
            result = subprocess.CompletedProcess([], returncode, "", "")
            with mock.patch.object(config.subprocess, "run", return_value=result):
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    self.run_main("--apply")
            self.assertEqual(self.path.read_text(), FIXTURE)
            self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_game_start_during_preparation_leaves_original(self):
        with mock.patch.object(config, "require_game_closed", side_effect=[None, ValueError("Game started")]):
            with self.assertRaises(ValueError):
                config.apply_changes(self.path, FIXTURE.encode(), config.patch_controls(FIXTURE).encode())
        self.assertEqual(self.path.read_text(), FIXTURE)
        self.assertEqual(len(list(self.path.parent.glob("*.bak"))), 1)
        self.assertEqual(len(list(self.path.parent.iterdir())), 2)

    def test_concurrent_file_change_refuses_before_backup(self):
        original = FIXTURE.encode()
        self.path.write_text(FIXTURE + "\n")
        with mock.patch.object(config, "require_game_closed"), self.assertRaisesRegex(ValueError, "changed"):
            config.apply_changes(self.path, original, config.patch_controls(FIXTURE).encode())
        self.assertEqual(self.path.read_text(), FIXTURE + "\n")
        self.assertEqual(list(self.path.parent.iterdir()), [self.path])

    def test_explicit_controls_path_is_required(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            config.main([])


if __name__ == "__main__":
    unittest.main()
