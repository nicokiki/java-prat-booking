import argparse
import copy
import json
import re
import struct
import subprocess
import zlib
from datetime import date, datetime
from pathlib import Path


OUTCOMES = {"Confirmed", "Unavailable", "Technical failure", "Informational"}
SUMMARIES = {
    "Reservation success message observed.",
    "No eligible tee time on the checked courses. Reservation not submitted.",
    "Reservation submission attempted; confirmation not observed. Check TeeOne before retrying.",
    "Booking stopped before reservation submission. Check the workflow logs.",
    "No booking row for the target date. Availability inspected for two players and 18 holes; no reservation attempted.",
    "No booking row for the target date. Availability inspected for two players; nine holes checked only where 18-hole times were unavailable. No reservation attempted.",
}
FIELDS = {
    "schemaVersion", "executionId", "executionDate", "startedAt", "finishedAt", "playDate",
    "players", "participants", "requested", "outcome", "booked", "blockedAt", "confirmedAt",
    "bookingDurationSeconds", "bookingFormStartedAt", "summary", "availability",
}
COURSES = {"TEE 1 ROSA": "rosa", "TEE 1 AMARILLO": "amarillo", "TEE 1 VERDE": "verde"}


def clean_screenshots(images, report):
    if not isinstance(images, list) or len(images) > 12:
        raise ValueError("Invalid screenshot list")
    clean = []
    seen = set()
    for image in images:
        if not isinstance(image, dict):
            raise ValueError("Invalid screenshot metadata")
        course, stage = image.get("course"), image.get("stage")
        holes = image.get("holes", 18)
        if type(holes) is not int or holes not in {9, 18}:
            raise ValueError("Invalid screenshot hole count")
        if holes == 9 and not any(snapshot.get("course") == course and snapshot.get("holes") == 9 for snapshot in report.get("availability", [])):
            raise ValueError("Nine-hole screenshot has no matching observation")
        if course not in COURSES or stage not in {"initial", "recheck"} or (course, holes, stage) in seen:
            raise ValueError("Invalid or duplicate screenshot course/stage")
        path = "data/executions/" + report["executionId"] + "/screenshots/" + COURSES[course] + ("-9" if holes == 9 else "") + "-" + stage + ".png"
        if image.get("path") != path or image.get("playDate") != report["playDate"]:
            raise ValueError("Screenshot belongs to another execution or play date")
        timestamp = datetime.fromisoformat(image["capturedAt"])
        if timestamp.utcoffset() is None:
            raise ValueError("Screenshot capture time must include an offset")
        if type(image.get("players")) is not int or image["players"] not in {2, 3, 4}:
            raise ValueError("Invalid screenshot player count")
        if report.get("players") is not None and image["players"] != report["players"]:
            raise ValueError("Screenshot player count does not match execution")
        if type(image.get("optionCount")) is not int or not 0 <= image["optionCount"] <= 500:
            raise ValueError("Invalid screenshot option count")
        clean.append({key: image[key] for key in
                      ("path", "course", "stage", "playDate", "capturedAt", "players", "optionCount")})
        clean[-1]["holes"] = holes
        if image.get("rendering") == "redacted-real-form-v1":
            dimensions = (image.get("width"), image.get("height"))
            validate_real_dimensions(dimensions)
            clean[-1].update(rendering="redacted-real-form-v1", width=dimensions[0], height=dimensions[1])
        elif image.get("rendering") not in {None, "sanitized-dropdown-v1"}:
            raise ValueError("Invalid screenshot rendering")
        seen.add((course, holes, stage))
    return clean


def validate_real_dimensions(dimensions):
    width, height = dimensions
    if type(width) is not int or type(height) is not int or not 1 <= width <= 4096 or not 1 <= height <= 30000 or width * height > 20000000:
        raise ValueError("Invalid real screenshot dimensions")


def public_png(path, option_count, real_size=None):
    if real_size is not None:
        validate_real_dimensions(real_size)
    if path.is_symlink() or path.stat().st_size > 20 * 1024 * 1024:
        raise ValueError("Unsafe or oversized PNG")
    data = path.read_bytes()
    signature = b"\x89PNG\r\n\x1a\n"
    if not data.startswith(signature):
        raise ValueError("Invalid PNG signature")
    offset, chunks, types = 8, [], []
    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError("Truncated PNG")
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + size + 12
        if end > len(data):
            raise ValueError("Truncated PNG chunk")
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:end - 4]
        if zlib.crc32(kind + payload) != struct.unpack(">I", data[end - 4:end])[0]:
            raise ValueError("PNG checksum mismatch")
        if not types:
            if kind != b"IHDR" or size != 13:
                raise ValueError("Invalid PNG header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            valid_size = (width, height) == real_size if real_size is not None else width == 840 and height in {max(140, 96 + option_count * 24) * 2, (300 + option_count * 24) * 2}
            if not valid_size or depth != 8 or color not in {2, 6} or any((compression, filtering, interlace)):
                raise ValueError("PNG does not match the full-page rendering")
        if kind in {b"IHDR", b"IDAT", b"IEND"}:
            chunks.append(data[offset:end])
        elif not kind[0] & 32:
            raise ValueError("Unsupported critical PNG chunk")
        types.append(kind)
        offset = end
        if kind == b"IEND":
            if size or offset != len(data):
                raise ValueError("Invalid PNG end")
            break
    if not types or types.count(b"IHDR") != 1 or b"IDAT" not in types or types[-1] != b"IEND":
        raise ValueError("Incomplete PNG")
    return signature + b"".join(chunks)


def load_screenshots(directory, report):
    manifest_path = directory / "tee-time-images.json"
    if not manifest_path.exists():
        return [], {}
    if directory.is_symlink() or manifest_path.is_symlink() or manifest_path.stat().st_size > 65536:
        raise ValueError("Unsafe screenshot manifest")
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != 1 or manifest.get("rendering") not in {"sanitized-dropdown-v1", "redacted-real-form-v1"} or manifest.get("executionId") != report["executionId"]:
        raise ValueError("Screenshot manifest provenance does not match execution")
    if not isinstance(manifest.get("images"), list) or len(manifest["images"]) > 12:
        raise ValueError("Invalid screenshot manifest")
    images, files = [], {}
    for entry in manifest["images"]:
        if not isinstance(entry, dict):
            raise ValueError("Invalid screenshot manifest entry")
        if entry.get("course") not in COURSES or entry.get("phase") not in {"initial-read", "initial-empty", "delayed-read-only"}:
            continue
        stage = {"initial": "initial", "delayed": "recheck"}.get(entry.get("stage"))
        if stage is None or (entry["phase"] == "delayed-read-only") != (stage == "recheck"):
            raise ValueError("Invalid screenshot stage")
        holes = entry.get("holes", 18)
        if type(holes) is not int or holes not in {9, 18}:
            raise ValueError("Invalid screenshot hole count")
        if holes == 9 and not any(snapshot.get("course") == entry["course"] and snapshot.get("holes") == 9 for snapshot in report.get("availability", [])):
            continue
        expected = report["playDate"].replace("-", "") + "-" + COURSES[entry["course"]] + "-p" + str(entry.get("players")) + ("-h9" if holes == 9 else "") + "-" + entry["stage"] + ".png"
        if entry.get("file") != expected or entry.get("playDate") != report["playDate"].replace("-", ""):
            raise ValueError("Unsafe screenshot file or mismatched play date")
        image = {key: entry.get(key) for key in ("course", "capturedAt", "players", "optionCount")}
        image.update(stage=stage, holes=holes, playDate=report["playDate"], path="data/executions/" + report["executionId"] + "/screenshots/" + COURSES[entry["course"]] + ("-9" if holes == 9 else "") + "-" + stage + ".png")
        real_size = None
        if manifest["rendering"] == "redacted-real-form-v1":
            real_size = (entry.get("width"), entry.get("height"))
            image.update(rendering="redacted-real-form-v1", width=real_size[0], height=real_size[1])
        images.append(image)
        clean_screenshots(images, report)
        files[image["path"]] = public_png(directory / expected, image["optionCount"], real_size)
    return images, files


def clean_report(report):
    if report.get("schemaVersion") != 1 or report.get("outcome") not in OUTCOMES | {"Uncertain"}:
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
    if clean["outcome"] == "Uncertain":
        clean["outcome"] = "Technical failure"
    for key in ("startedAt", "finishedAt", "bookingFormStartedAt", "blockedAt", "confirmedAt"):
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
        if type(snapshot["holes"]) is not int or snapshot["holes"] not in {9, 18}:
            raise ValueError("Invalid snapshot hole count")
        if observation.get("fallbackReason") is not None:
            if observation["fallbackReason"] != "18-not-offered" or snapshot["holes"] != 9:
                raise ValueError("Invalid nine-hole fallback reason")
            snapshot["fallbackReason"] = "18-not-offered"
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
    for snapshot in clean["availability"]:
        if snapshot["holes"] != 9:
            continue
        if snapshot["players"] != 2 or snapshot["selectedTime"] is not None or clean["players"] != 2 or clean["participants"] or any(clean[key] is not None for key in
                ("requested", "booked", "bookingFormStartedAt", "blockedAt", "confirmedAt", "bookingDurationSeconds")):
            raise ValueError("Nine-hole fallback is observation-only")
        eighteen = next((item for item in clean["availability"] if item["course"] == snapshot["course"] and item["holes"] == 18), None)
        if eighteen is not None and eighteen["options"] or eighteen is None and snapshot.get("fallbackReason") != "18-not-offered":
            raise ValueError("Nine-hole fallback requires empty eighteen-hole times or eighteen not offered")
    if report.get("screenshots"):
        clean["screenshots"] = clean_screenshots(report["screenshots"], clean)
    if clean["outcome"] == "Informational":
        if clean["players"] != 2 or clean["participants"] or any(clean[key] is not None for key in
                ("requested", "booked", "bookingFormStartedAt", "blockedAt", "confirmedAt", "bookingDurationSeconds")):
            raise ValueError("Informational report cannot claim booking activity or participants")
        courses = {snapshot["course"] for snapshot in clean["availability"]}
        variants = {(snapshot["course"], snapshot["holes"]) for snapshot in clean["availability"]}
        if len(clean["availability"]) != len(variants) or courses not in (
                {"TEE 1 ROSA", "TEE 1 AMARILLO"}, {"TEE 1 ROSA", "TEE 1 AMARILLO", "TEE 1 VERDE"}):
            raise ValueError("Informational report must inspect Rosa and Amarillo, with Verde optional")
        if any(snapshot["players"] != 2 or snapshot["selectedTime"] is not None
               for snapshot in clean["availability"]):
            raise ValueError("Informational snapshots must use two players and no selected time")
    return clean


def merge_report(path, report):
    existing = json.loads(path.read_text()) if path.exists() else {"schemaVersion": 1, "executions": []}
    if existing.get("schemaVersion") != 1 or not isinstance(existing.get("executions"), list):
        raise ValueError("Invalid dashboard archive")
    reports = {entry["executionId"]: clean_report(entry) for entry in existing["executions"]}
    incoming = clean_report(report)
    if not incoming.get("screenshots") and reports.get(report["executionId"], {}).get("screenshots"):
        incoming["screenshots"] = reports[report["executionId"]]["screenshots"]
    reports[report["executionId"]] = incoming
    return {"schemaVersion": 1, "executions": sorted(reports.values(), key=lambda entry:
            (entry["executionDate"], entry.get("startedAt") or "", entry["executionId"]))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--diagnostics", type=Path)
    parser.add_argument("--dry-run", action="store_true")
    arguments = parser.parse_args()
    report = clean_report(json.loads(arguments.report.read_text()))
    files = {}
    if arguments.diagnostics:
        try:
            images, files = load_screenshots(arguments.diagnostics, report)
            if images:
                report["screenshots"] = images
        except (OSError, ValueError, KeyError, TypeError):
            print("Warning: screenshot validation failed; publishing the booking result without new images")
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
        for relative, png in files.items():
            path = repository / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(png)
        subprocess.run(["git", "add", "--", "data/executions.json", *files], cwd=repository, check=True)
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