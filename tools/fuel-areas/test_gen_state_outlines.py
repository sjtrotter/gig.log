"""Round-trip tests of the state outline generator (#1687). Run: python3 -I -m unittest discover -s tools/fuel-areas -p 'test_*.py'

The shapefile is hand-built by the test (two squares, one with a hole) into a temporary directory; nothing is
downloaded.
"""
from __future__ import annotations

import contextlib
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import gen_state_outlines as gen

# AA: a 2-degree square with a 1-degree hole. BB: one square at negative longitudes.
SQUARES = {
    "BB": [[(-100.0, 30.0), (-100.0, 31.00005), (-99.0, 31.00005), (-99.0, 30.0), (-100.0, 30.0)]],
    "AA": [
        [(10.0, 40.0), (10.0, 42.0), (12.0, 42.0), (12.0, 40.0), (10.0, 40.0)],
        [(10.5, 40.5), (11.5, 40.5), (11.5, 41.5), (10.5, 41.5), (10.5, 40.5)],
    ],
}


def shp_bytes(records: list[list[list[tuple[float, float]]]]) -> bytes:
    body = bytearray()
    for number, rings in enumerate(records, start=1):
        points = [p for ring in rings for p in ring]
        parts, start = [], 0
        for ring in rings:
            parts.append(start)
            start += len(ring)
        xs, ys = [p[0] for p in points], [p[1] for p in points]
        content = struct.pack("<i4d2i", gen.SHAPE_POLYGON, min(xs), min(ys), max(xs), max(ys), len(parts), len(points))
        content += struct.pack(f"<{len(parts)}i", *parts)
        content += b"".join(struct.pack("<2d", *p) for p in points)
        body += struct.pack(">ii", number, len(content) // 2) + content
    header = struct.pack(">i5ii", 9994, 0, 0, 0, 0, 0, (100 + len(body)) // 2)
    header += struct.pack("<ii", 1000, gen.SHAPE_POLYGON) + struct.pack("<8d", *([0.0] * 8))
    return header + bytes(body)


def dbf_bytes(values: list[str]) -> bytes:
    fields = [("NAME", 8), (gen.POSTAL_FIELD, 2)]
    header_len = 32 + 32 * len(fields) + 1
    record_len = 1 + sum(length for _, length in fields)
    out = bytearray(struct.pack("<B3BIHH20x", 3, 124, 1, 1, len(values), header_len, record_len))
    for name, length in fields:
        out += name.encode("ascii").ljust(11, b"\x00") + b"C" + b"\x00" * 4 + bytes([length, 0]) + b"\x00" * 14
    out += b"\x0D"
    for value in values:
        out += b" " + b"Name".ljust(8) + value.encode("ascii").ljust(2)
    return bytes(out + b"\x1A")


def read_varint(data: bytes, at: int) -> tuple[int, int]:
    value, shift = 0, 0
    while True:
        byte = data[at]
        at += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, at
        shift += 7


def read_zigzag(data: bytes, at: int) -> tuple[int, int]:
    raw, at = read_varint(data, at)
    return (raw >> 1) ^ -(raw & 1), at


def decode(data: bytes) -> dict:
    """A test-side reader of format v1, independent of the encoder."""
    assert data[:4] == b"GLSO"
    at = 4
    version, at = read_varint(data, at)
    scale, at = read_varint(data, at)
    count, at = read_varint(data, at)
    states = {}
    for _ in range(count):
        postal = data[at:at + 2].decode("ascii")
        at += 2
        bbox = []
        for _ in range(4):
            value, at = read_zigzag(data, at)
            bbox.append(value)
        rings_count, at = read_varint(data, at)
        rings = []
        for _ in range(rings_count):
            points_count, at = read_varint(data, at)
            lon, lat, ring = 0, 0, []
            for _ in range(points_count):
                d_lon, at = read_zigzag(data, at)
                d_lat, at = read_zigzag(data, at)
                lon, lat = lon + d_lon, lat + d_lat
                ring.append((lon, lat))
            rings.append(ring)
        states[postal] = {"bbox": bbox, "rings": rings}
    assert at == len(data), "trailing bytes"
    return {"version": version, "scale": scale, "states": states}


class GenStateOutlinesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.zip = Path(self.tmp.name) / "squares.zip"
        order = list(SQUARES)  # BB first: the output must sort by postal code regardless
        with zipfile.ZipFile(self.zip, "w") as archive:
            archive.writestr("squares.shp", shp_bytes([SQUARES[k] for k in order]))
            archive.writestr("squares.dbf", dbf_bytes(order))

    def run_gen(self, name: str) -> bytes:
        out = Path(self.tmp.name) / name
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, gen.main(["gen", str(self.zip), str(out)]))
        return out.read_bytes()

    def test_round_trip(self):
        decoded = decode(self.run_gen("a.bin"))
        self.assertEqual(1, decoded["version"])
        self.assertEqual(10_000, decoded["scale"])
        self.assertEqual(["AA", "BB"], list(decoded["states"]))
        aa = decoded["states"]["AA"]
        self.assertEqual([100_000, 400_000, 120_000, 420_000], aa["bbox"])
        self.assertEqual(2, len(aa["rings"]))
        self.assertEqual([(105_000, 405_000), (115_000, 405_000), (115_000, 415_000), (105_000, 415_000),
                          (105_000, 405_000)], aa["rings"][1])
        bb = decoded["states"]["BB"]
        # 31.00005 is stored just above the half, so it rounds to 310001.
        self.assertEqual([-1_000_000, 300_000, -990_000, 310_001], bb["bbox"])
        self.assertEqual((-1_000_000, 300_000), bb["rings"][0][0])

    def test_rounding_is_half_away_from_zero(self):
        self.assertEqual(3, gen.scaled(0.00025))  # 0.00025 is stored just above the half
        self.assertEqual(-3, gen.scaled(-0.00025))
        self.assertEqual(1, gen.scaled(0.00005000000000001))
        self.assertEqual(310_001, gen.scaled(31.00005))
        self.assertEqual(-1_000_000, gen.scaled(-100.0))

    def test_output_is_deterministic(self):
        self.assertEqual(self.run_gen("a.bin"), self.run_gen("b.bin"))

    def test_a_duplicate_postal_code_fails(self):
        with zipfile.ZipFile(self.zip, "w") as archive:
            archive.writestr("x.shp", shp_bytes([SQUARES["AA"], SQUARES["BB"]]))
            archive.writestr("x.dbf", dbf_bytes(["AA", "AA"]))
        with self.assertRaises(gen.OutlineError):
            gen.states_from_zip(self.zip)


if __name__ == "__main__":
    unittest.main()
