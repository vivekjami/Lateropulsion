#!/usr/bin/env python3
"""Read `.lpx` session logs (core/timeseries/LpxFormat.kt) and export CSV or Parquet (ADR-011).

Usage:
  python3 tools/analysis/lpx_reader.py session.lpx            # prints header + trailer status, writes session.csv
  python3 tools/analysis/lpx_reader.py session.lpx --parquet  # also writes session.parquet (needs pyarrow)
"""
import hashlib
import struct
import sys

HEADER_LEN, RECORD_LEN, TRAILER_LEN = 256, 22, 64
REC = struct.Struct("<Ihhhhhhhh H".replace(" ", ""))


def read(path):
    data = open(path, "rb").read()
    assert data[:4] == b"LPX1", "not an lpx file"
    version, header_len, record_len, rate = struct.unpack_from("<HHHH", data, 4)
    uuid = data[12:48].split(b"\0")[0].decode()
    t0_utc, t0_mono = struct.unpack_from("<qq", data, 48)
    device = data[64:96].split(b"\0")[0].decode()
    flags, theta_ref = struct.unpack_from("<Id", data, 96)
    header = dict(version=version, sample_rate_hz=rate, session_uuid=uuid, t0_utc_ms=t0_utc, t0_mono_ns=t0_mono, device_profile_id=device, flags=flags, theta_ref_deg=theta_ref)
    trailer = None
    body_end = len(data)
    if len(data) >= HEADER_LEN + TRAILER_LEN and data[-TRAILER_LEN:-TRAILER_LEN + 4] == b"LPXE":
        count, = struct.unpack_from("<Q", data, len(data) - TRAILER_LEN + 4)
        sha = data[len(data) - TRAILER_LEN + 12: len(data) - TRAILER_LEN + 44]
        reason, = struct.unpack_from("<H", data, len(data) - TRAILER_LEN + 44)
        trailer = dict(sample_count=count, sha256=sha.hex(), close_reason=reason)
        body_end = len(data) - TRAILER_LEN
    body = data[HEADER_LEN:body_end]
    whole = len(body) // RECORD_LEN
    records = []
    for i in range(whole):
        t, tr, tf, gx, gy, gz, wx, wy, wz, fl = REC.unpack_from(body, i * RECORD_LEN)
        records.append((t, tr / 100.0, tf / 100.0, gx / 1e4, gy / 1e4, gz / 1e4, wx / 100.0, wy / 100.0, wz / 100.0, fl))
    status = "MISSING" if trailer is None else ("COUNT_MISMATCH" if trailer["sample_count"] != whole else ("HASH_MISMATCH" if hashlib.sha256(body[:whole * RECORD_LEN]).hexdigest() != trailer["sha256"] else "VALID"))
    return header, records, trailer, status


COLUMNS = ["t_ms", "theta_raw_deg", "theta_filt_deg", "gx", "gy", "gz", "wx_rad_s", "wy_rad_s", "wz_rad_s", "flags"]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    header, records, trailer, status = read(path)
    print("header:", header)
    print("trailer:", trailer, "status:", status, "records:", len(records))
    base = path.rsplit(".", 1)[0]
    with open(base + ".csv", "w") as fh:
        fh.write(",".join(COLUMNS) + "\n")
        for r in records:
            fh.write(",".join(str(x) for x in r) + "\n")
    print("wrote", base + ".csv")
    if "--parquet" in sys.argv:
        try:
            import pyarrow as pa
            import pyarrow.parquet as pq
        except ImportError:
            print("pyarrow not installed: pip install pyarrow")
            return 2
        table = pa.table({c: [r[i] for r in records] for i, c in enumerate(COLUMNS)})
        pq.write_table(table, base + ".parquet")
        print("wrote", base + ".parquet")
    return 0


if __name__ == "__main__":
    sys.exit(main())
