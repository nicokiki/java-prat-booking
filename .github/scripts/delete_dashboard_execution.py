import argparse
import json
import re
from pathlib import Path


def delete_execution(archive, execution_id):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", execution_id):
        raise ValueError("Invalid execution ID; nothing deleted")
    if not isinstance(archive, dict) or archive.get("schemaVersion") != 1 or not isinstance(archive.get("executions"), list):
        raise ValueError("Invalid archive; nothing deleted")
    identifiers = []
    for execution in archive["executions"]:
        if not isinstance(execution, dict) or not isinstance(execution.get("executionId"), str):
            raise ValueError("Invalid execution; nothing deleted")
        identifiers.append(execution["executionId"])
    if len(set(identifiers)) != len(identifiers):
        raise ValueError("Duplicate execution IDs; nothing deleted")
    if execution_id not in identifiers:
        raise ValueError("Execution not found; nothing deleted")
    updated = dict(archive)
    updated["executions"] = [execution for execution in archive["executions"] if execution["executionId"] != execution_id]
    return updated


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--execution-id", required=True)
    parser.add_argument("--confirm-delete", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not args.confirm_delete and not args.dry_run:
        parser.error("Explicit --confirm-delete is required")
    archive = json.loads(args.archive.read_text(encoding="utf-8"))
    updated = delete_execution(archive, args.execution_id)
    if args.dry_run:
        print("Validated deletion of " + args.execution_id + "; no data changed")
        return
    temporary = args.archive.with_suffix(".tmp")
    temporary.write_text(json.dumps(updated, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.archive)
    print("Removed execution " + args.execution_id + " from dashboard archive only")


if __name__ == "__main__":
    main()