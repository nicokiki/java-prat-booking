import importlib.util
import json
import tempfile
import unittest
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
        for key, value in [("booked", "Rosa 08:00"), ("confirmedAt", "2026-11-15T20:00:02+01:00"),
                           ("players", 3), ("participants", [{"role": "socio", "licencia": "DEMO"}]),
                           ("availability", report["availability"][:2])]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                publisher.clean_report({**report, key: value})


if __name__ == "__main__":
    unittest.main()