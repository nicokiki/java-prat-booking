import argparse
import copy
import json
import re
import subprocess
from datetime import date, datetime
from pathlib import Path


OUTCOMES = {"Confirmed", "Unavailable", "Uncertain", "Technical failure"}
SUMMARIES = {
    "Reservation success message observed.",
    "No eligible tee time on the checked courses. Reservation not submitted.",
    "Reservation submission attempted; confirmation not observed. Check TeeOne before retrying.",
    "Booking stopped before reservation submission. Check the workflow logs.",
}
FIELDS = {
    "schemaVersion", "executionId", "executionDate", "startedAt", "finishedAt", "playDate",
    "players", "participants", "requested", "outcome", "booked", "blockedAt", "confirmedAt",
    "bookingDurationSeconds", "summary", "availability",
}


def clean_report(report):
    if report.get("schemaVersion") != 1 or report.get("outcome") not in OUTCOMES:
        raise ValueError("Unsupported report schema or outcome")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", report.get("executionId", "")):
        raise ValueError("Invalid execution ID")
    for key in ("executionDate", "playDate"):
        date.fromisoformat(report[key])
    if report.get("summary") not in SUMMARIES:
        raise ValueError("Report summary is not allowlisted")
    if report["outcome"] == "Confirmed" and not isinstance(report.get("booked"), str):
        raise ValueError("Confirmed report must include the booked slot")
    if report["outcome"] != "Confirmed" and report.get("booked") is not None:
        raise ValueError("Unconfirmed report cannot claim a booking")
    clean = {key: copy.deepcopy(report.get(key)) for key in FIELDS}
    for key in ("startedAt", "finishedAt", "blockedAt", "confirmedAt"):
        if clean[key] is not None:
            value = datetime.fromisoformat(clean[key])
            if value.utcoffset() is None:
                raise ValueError("Timestamps must include an offset")
    for key in ("requested", "booked"):
        if clean[key] is not None and (not isinstance(clean[key], str) or len(clean[key]) > 200):
            raise ValueError("Invalid booking label")
    clean["participants"] = []
    for person in report.get("participants", []):
        if person.get("role") not in {"booking-holder", "socio"}:
            raise ValueError("Invalid participant role")
        participant = {key: person.get(key) for key in ("role", "name", "licencia")}
        for key in ("name", "licencia"):
            if participant[key] is not None and (not isinstance(participant[key], str) or len(participant[key]) > 160):
                raise ValueError("Invalid participant identity")
        clean["participants"].append(participant)
    clean["availability"] = []
    for observation in report.get("availability", []):
        snapshot = {key: observation.get(key) for key in
                    ("executionId", "playDate", "course", "capturedAt", "players", "holes", "selectedTime")}
        if snapshot["executionId"] != clean["executionId"] or snapshot["playDate"] != clean["playDate"]:
            raise ValueError("Snapshot belongs to another execution or play date")
        datetime.fromisoformat(snapshot["capturedAt"])
        if not isinstance(snapshot["course"], str) or len(snapshot["course"]) > 100:
            raise ValueError("Invalid course")
        snapshot["options"] = []
        for offered in observation.get("options", []):
            if not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", offered.get("time", "")):
                raise ValueError("Invalid tee time")
            places = offered.get("places")
            if places is not None and (type(places) is not int or not 0 <= places <= 99):
                raise ValueError("Invalid capacity")
            if type(offered.get("enabled")) is not bool:
                raise ValueError("Invalid enabled state")
            snapshot["options"].append({key: offered.get(key) for key in ("time", "places", "enabled")})
        clean["availability"].append(snapshot)
    return clean


def merge_report(path, report):
    existing = json.loads(path.read_text()) if path.exists() else {"schemaVersion": 1, "executions": []}
    if existing.get("schemaVersion") != 1 or not isinstance(existing.get("executions"), list):
        raise ValueError("Invalid dashboard archive")
    reports = {entry["executionId"]: clean_report(entry) for entry in existing["executions"]}
    reports[report["executionId"]] = clean_report(report)
    return {"schemaVersion": 1, "executions": sorted(reports.values(), key=lambda entry:
            (entry["executionDate"], entry.get("startedAt") or "", entry["executionId"]))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()
    report = clean_report(json.loads(arguments.report.read_text()))
    repository = arguments.repository.resolve()
    destination = repository / "data" / "executions.json"
    if arguments.dry_run:
        merge_report(destination, report)
        print("Validated dashboard merge; no files changed or pushed")
        return
    for attempt in range(3):
        archive = merge_report(destination, report)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".tmp")
        temporary.write_text(json.dumps(archive, ensure_ascii=True, indent=2) + "\n")
        temporary.replace(destination)
        subprocess.run(["git", "add", "--", "data/executions.json"], cwd=repository, check=True)
        if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=repository).returncode == 0:
            print("Execution already published")
            return
        subprocess.run(["git", "commit", "-m", "Record booking execution " + report["executionId"]], cwd=repository, check=True)
        if subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=repository).returncode == 0:
            return
        if attempt < 2:
            subprocess.run(["git", "fetch", "origin", "main"], cwd=repository, check=True)
            subprocess.run(["git", "reset", "--mixed", "origin/main"], cwd=repository, check=True)
            destination.write_bytes(subprocess.check_output(
                ["git", "show", "origin/main:data/executions.json"], cwd=repository))
    raise RuntimeError("Publishing failed after three Git push attempts; booking was not retried")


if __name__ == "__main__":
    main()