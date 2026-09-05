#!/usr/bin/env python3
"""Static scan for PHI leaking into logs (REQ-SEC-004).

Rules
-----
1. `android.util.Log` may only be imported inside the logging adapter (app/.../logging/).
2. A logging call (LpLog.x / Log.x / println / Timber.x) may not reference an identifier
   that names a PHI field: name, mrn, contact, phone, email, address, dob.
3. `toString()` of Patient/PatientIdentity inside a log call is forbidden.

Exit status 1 with a list of offending lines if any rule fails.
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
LOG_CALL = re.compile(r"\b(LpLog\.[diwe]|Log\.[vdiwe]|println|Timber\.[vdiwe]|logger\.\w+)\s*\(")
PHI_IDENT = re.compile(
    r"\b(\w*(?:[Nn]ame|[Mm]rn|MRN|[Cc]ontact|[Pp]hone|[Ee]mail|[Aa]ddress|[Dd]ob|dateOfBirth)\w*)\b"
)
SAFE_IDENTS = {
    "displayName", "tagName", "protocolName", "scaleName", "blockName", "exerciseName", "fileName",
    "modelName", "deviceName", "headsetName", "className", "name", "sensorName", "threadName",
    "siteName", "packageName", "cueName", "metricName", "fieldName", "keyName", "typeName",
    "enumName", "columnName", "tableName", "eventName", "itemName", "sectionName", "profileName",
}
# `name` alone is ambiguous; treat it as PHI only when qualified by patient/identity.
PATIENT_QUALIFIED = re.compile(r"\b(patient|identity|ident)\w*\.(name|mrn|contact|phone|email|address|dob)\b", re.I)
IMPORT_LOG = re.compile(r"^\s*import\s+android\.util\.Log\b")
ALLOWED_LOG_IMPORT_DIRS = ("/logging/",)
TO_STRING = re.compile(r"\b(patient|identity)\w*(\.toString\(\)|\s*\$\{)", re.I)

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "schemas"}


def scan_file(path):
    problems = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for n, line in enumerate(fh, 1):
            if IMPORT_LOG.search(line) and not any(d in path for d in ALLOWED_LOG_IMPORT_DIRS):
                problems.append((path, n, "android.util.Log imported outside the logging adapter", line.strip()))
            if LOG_CALL.search(line):
                if PATIENT_QUALIFIED.search(line):
                    problems.append((path, n, "PHI field referenced in a log call", line.strip()))
                    continue
                for ident in PHI_IDENT.findall(line):
                    if ident in SAFE_IDENTS or ident.endswith("Name") and ident not in {"patientName", "firstName", "lastName", "fullName"}:
                        continue
                    if ident.lower() in {"name"}:
                        continue
                    problems.append((path, n, f"suspicious identifier '{ident}' in a log call", line.strip()))
                if TO_STRING.search(line):
                    problems.append((path, n, "patient object stringified in a log call", line.strip()))
    return problems


def main():
    problems = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if f.endswith((".kt", ".java")):
                problems.extend(scan_file(os.path.join(dirpath, f)))
    if problems:
        print("PHI log scan FAILED:")
        for path, n, why, text in problems:
            print(f"  {os.path.relpath(path, ROOT)}:{n}: {why}\n      {text}")
        return 1
    print("PHI log scan passed (no PHI-bearing log statements found).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
