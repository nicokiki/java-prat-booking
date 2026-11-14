import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("delete_dashboard", Path(__file__).with_name("delete_dashboard_execution.py"))
delete_dashboard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delete_dashboard)


class DeleteExecutionTest(unittest.TestCase):
    def setUp(self):
        self.archive = {"schemaVersion": 1, "executions": [
            {"executionId": "fixture-1", "availability": [{"options": ["fixture"]}]},
            {"executionId": "fixture-2", "participants": [{"name": "Fixture"}]},
        ]}

    def test_removes_only_exact_execution_and_preserves_source(self):
        original = copy.deepcopy(self.archive)
        updated = delete_dashboard.delete_execution(self.archive, "fixture-1")
        self.assertEqual(updated["executions"], [original["executions"][1]])
        self.assertEqual(self.archive, original)

    def test_rejects_invalid_missing_and_duplicate_identifiers(self):
        for identifier in ["fixture", "missing", "../../data", ""]:
            with self.assertRaises(ValueError):
                delete_dashboard.delete_execution(self.archive, identifier)
        self.archive["executions"].append(self.archive["executions"][0])
        with self.assertRaises(ValueError):
            delete_dashboard.delete_execution(self.archive, "fixture-1")

    def test_rejects_invalid_archive(self):
        for archive in [{}, {"schemaVersion": 2, "executions": []}, {"schemaVersion": 1, "executions": [None]}]:
            with self.assertRaises(ValueError):
                delete_dashboard.delete_execution(archive, "fixture-1")

    def test_dry_run_and_unconfirmed_commands_never_write(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "executions.json")
            path.write_text(json.dumps(self.archive))
            original = path.read_bytes()
            args = ["delete", str(path), "--execution-id", "fixture-1"]
            with patch.object(sys, "argv", args), self.assertRaises(SystemExit):
                delete_dashboard.main()
            with patch.object(sys, "argv", args + ["--dry-run"]):
                delete_dashboard.main()
            self.assertEqual(path.read_bytes(), original)

    def test_confirmed_command_updates_only_fixture_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "executions.json")
            path.write_text(json.dumps({"schemaVersion": 1, "executions": [self.archive["executions"][0]]}))
            with patch.object(sys, "argv", ["delete", str(path), "--execution-id", "fixture-1", "--confirm-delete"]):
                delete_dashboard.main()
            self.assertEqual(json.loads(path.read_text()), {"schemaVersion": 1, "executions": []})
            self.assertFalse(path.with_suffix(".tmp").exists())


if __name__ == "__main__":
    unittest.main()