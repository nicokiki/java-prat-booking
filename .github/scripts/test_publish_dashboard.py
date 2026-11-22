import importlib.util
import json
import tempfile
import struct
import unittest
import zlib
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish_dashboard.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublisherTest(unittest.TestCase):
    def report(self, execution="fixture-1"):
        return {"schemaVersion": 1, "executionId": execution, "executionDate": "2026-11-14",
                "playDate": "2026-11-15", "players": 2, "outcome": "Uncertain", "booked": None,
                "summary": "Reservation submission attempted; confirmation not observed. Check TeeOne before retrying.",
                "participants": [{"role": "socio", "name": "Example", "licencia": "DEMO-2", "password": "NEVER-PUBLISH"}],
                "availability": [], "credentials": "NEVER-PUBLISH"}

    def test_allowlist_and_idempotent_history(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "executions.json"
            first = publisher.merge_report(path, self.report())
            self.assertEqual("Technical failure", first["executions"][0]["outcome"])
            self.assertIn("Check TeeOne before retrying", first["executions"][0]["summary"])
            self.assertNotIn("NEVER-PUBLISH", json.dumps(first))
            path.write_text(json.dumps(first))
            second = publisher.merge_report(path, self.report("fixture-2"))
            self.assertEqual(2, len(second["executions"]))
            path.write_text(json.dumps(second))
            self.assertEqual(2, len(publisher.merge_report(path, self.report())["executions"]))

    def test_rejects_raw_error_or_false_confirmation(self):
        invalid = self.report()
        invalid["summary"] = "Secret-bearing exception text"
        with self.assertRaises(ValueError):
            publisher.clean_report(invalid)

        invalid = self.report()
        invalid["booked"] = "Rosa 08:30"
        with self.assertRaises(ValueError):
            publisher.clean_report(invalid)

    def test_push_retry_reloads_remote_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            destination = repository / "data" / "executions.json"
            destination.parent.mkdir()
            destination.write_text(json.dumps({"schemaVersion": 1, "executions": [self.report("fixture-old")]}))
            source = repository / "input.json"
            source.write_text(json.dumps(self.report("fixture-new")))
            remote = {"schemaVersion": 1, "executions": [self.report("fixture-old"), self.report("fixture-concurrent")]}
            pushes = []
            def run(command, **kwargs):
                if command[1] == "push":
                    pushes.append(command)
                    return SimpleNamespace(returncode=1 if len(pushes) == 1 else 0)
                return SimpleNamespace(returncode=1 if command[1] == "diff" else 0)
            with patch("sys.argv", ["publisher", str(source), str(repository)]), \
                    patch.object(publisher.subprocess, "run", side_effect=run), \
                    patch.object(publisher.subprocess, "check_output", return_value=json.dumps(remote).encode()):
                publisher.main()
            archive = json.loads(destination.read_text())
            self.assertEqual({"fixture-old", "fixture-new", "fixture-concurrent"},
                             {report["executionId"] for report in archive["executions"]})

    def test_informational_reports_are_observation_only(self):
        report = self.report()
        report.update(outcome="Informational", participants=[], summary=
                      "No booking row for the target date. Availability inspected for two players and 18 holes; no reservation attempted.")
        report["availability"] = [{"executionId": report["executionId"], "playDate": report["playDate"],
                "course": course, "capturedAt": "2026-11-15T20:00:02+01:00", "players": 2, "holes": 18,
                "selectedTime": None, "options": []} for course in ["TEE 1 ROSA", "TEE 1 AMARILLO", "TEE 1 VERDE"]]
        self.assertEqual("Informational", publisher.clean_report(report)["outcome"])
        self.assertEqual("Informational", publisher.clean_report({**report, "availability": report["availability"][:2]})["outcome"])
        for key, value in [("booked", "Rosa 08:00"), ("confirmedAt", "2026-11-15T20:00:02+01:00"),
                           ("bookingFormStartedAt", "2026-11-15T20:00:01+01:00"),
                           ("players", 3), ("participants", [{"role": "socio", "licencia": "DEMO"}]),
                           ("availability", report["availability"][:1]),
                           ("availability", report["availability"][:2] * 2)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                publisher.clean_report({**report, key: value})

    def test_form_start_is_allowlisted_optional_and_offset_aware(self):
        report = self.report()
        self.assertIsNone(publisher.clean_report(report)["bookingFormStartedAt"])
        report["bookingFormStartedAt"] = "2026-11-14T20:00:01+01:00"
        self.assertEqual(report["bookingFormStartedAt"], publisher.clean_report(report)["bookingFormStartedAt"])
        report["bookingFormStartedAt"] = "2026-11-14T20:00:01"
        with self.assertRaises(ValueError):
            publisher.clean_report(report)

    def screenshot_fixture(self, directory):
        def chunk(kind, payload):
            return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
        header = struct.pack(">IIBBBBB", 840, 288, 8, 2, 0, 0, 0)
        png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"tEXt", b"private\0NEVER-PUBLISH") + chunk(b"IDAT", zlib.compress(b"\0" * ((840 * 3 + 1) * 288))) + chunk(b"IEND", b"")
        entry = {"course": "TEE 1 ROSA", "stage": "initial", "playDate": "20261115", "players": 2,
                 "capturedAt": "2026-11-14T20:00:06+01:00", "optionCount": 2,
                 "phase": "initial-read", "file": "20261115-rosa-p2-initial.png"}
        manifest = {"schemaVersion": 1, "rendering": "sanitized-dropdown-v1", "executionId": "fixture-1", "images": [entry]}
        (directory / entry["file"]).write_bytes(png)
        (directory / "tee-time-images.json").write_text(json.dumps(manifest))
        (directory / "tee-time-html.log").write_text("NEVER-PUBLISH raw HTML")
        return manifest

    def test_pngs_are_per_execution_allowlisted_and_metadata_stripped(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.screenshot_fixture(directory)
            images, files = publisher.load_screenshots(directory, self.report())
            report = publisher.clean_report({**self.report(), "screenshots": images})
            self.assertEqual("data/executions/fixture-1/screenshots/rosa-initial.png", report["screenshots"][0]["path"])
            self.assertEqual(1, len(files))
            self.assertNotIn(b"NEVER-PUBLISH", next(iter(files.values())))
            archive = directory / "executions.json"
            archive.write_text(json.dumps({"schemaVersion": 1, "executions": [report]}))
            self.assertEqual(images, publisher.merge_report(archive, self.report())["executions"][0]["screenshots"])

    def test_manifest_rejects_wrong_execution_unsafe_paths_and_invalid_png(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = self.screenshot_fixture(directory)
            for update in [{"executionId": "other-run"}, {"rendering": "raw-html"},
                           {"images": [{**manifest["images"][0], "file": "../secret.png"}]},
                           {"images": [{**manifest["images"][0], "players": 3}]}]:
                (directory / "tee-time-images.json").write_text(json.dumps({**manifest, **update}))
                with self.assertRaises(ValueError):
                    publisher.load_screenshots(directory, self.report())
            (directory / "tee-time-images.json").write_text(json.dumps(manifest))
            (directory / manifest["images"][0]["file"]).write_bytes(b"not a PNG")
            with self.assertRaises(ValueError):
                publisher.load_screenshots(directory, self.report())

    def test_image_dry_run_writes_nothing_and_bad_images_do_not_block_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.screenshot_fixture(directory)
            repository = directory / "dashboard"
            repository.mkdir()
            source = directory / "report.json"
            source.write_text(json.dumps(self.report()))
            args = ["publisher", str(source), str(repository), "--diagnostics", str(directory)]
            with patch("sys.argv", args + ["--dry-run"]):
                publisher.main()
            self.assertEqual([], list(repository.iterdir()))
            (directory / "20261115-rosa-p2-initial.png").write_bytes(b"invalid")
            with patch("sys.argv", args), patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
                with patch.object(publisher.subprocess, "check_output", return_value=b'{"schemaVersion":1,"executions":[]}'):
                    with self.assertRaises(RuntimeError):
                        publisher.main()
            report = json.loads((repository / "data" / "executions.json").read_text())["executions"][0]
            self.assertNotIn("screenshots", report)

    def test_publication_copies_and_stages_pngs_without_raw_logs(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.screenshot_fixture(directory)
            repository = directory / "dashboard"
            repository.mkdir()
            source = directory / "report.json"
            source.write_text(json.dumps(self.report()))
            def run(command, **kwargs):
                return SimpleNamespace(returncode=1 if command[1] == "diff" else 0)
            with patch("sys.argv", ["publisher", str(source), str(repository), "--diagnostics", str(directory)]), \
                    patch.object(publisher.subprocess, "run", side_effect=run) as commands:
                publisher.main()
            relative = "data/executions/fixture-1/screenshots/rosa-initial.png"
            self.assertTrue((repository / relative).exists())
            self.assertNotIn(b"NEVER-PUBLISH", (repository / relative).read_bytes())
            self.assertFalse(list(repository.rglob("*.log")))
            self.assertFalse(list(repository.rglob("tee-time-images.json")))
            commands.assert_any_call(["git", "add", "--", "data/executions.json", relative], cwd=repository.resolve(), check=True)
            published = json.loads((repository / "data" / "executions.json").read_text())["executions"][0]
            self.assertEqual(relative, published["screenshots"][0]["path"])

    def test_malformed_manifest_and_header_only_png_fail_safely(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = self.screenshot_fixture(directory)
            for invalid in [[], {**manifest, "images": [None]}]:
                (directory / "tee-time-images.json").write_text(json.dumps(invalid))
                with self.assertRaises(ValueError):
                    publisher.load_screenshots(directory, self.report())
            (directory / "tee-time-images.json").write_text(json.dumps(manifest))
            (directory / manifest["images"][0]["file"]).write_bytes(b"\x89PNG\r\n\x1a\n")
            with self.assertRaises(ValueError):
                publisher.load_screenshots(directory, self.report())

    def test_unsettled_filter_and_readiness_failure_captures_are_not_published(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            manifest = self.screenshot_fixture(directory)
            for phase in ["filter-failure", "readiness-failure"]:
                entry = {**manifest["images"][0], "phase": phase}
                (directory / "tee-time-images.json").write_text(json.dumps({**manifest, "images": [entry]}))
                self.assertEqual(([], {}), publisher.load_screenshots(directory, self.report()))


if __name__ == "__main__":
    unittest.main()