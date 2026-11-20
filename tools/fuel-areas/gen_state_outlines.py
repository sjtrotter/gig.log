"""Write gig.log's bundled U.S. state outlines (#1687) from the Census cartographic boundary zip.

Run: python3 -I tools/fuel-areas/gen_state_outlines.py <cb_2024_us_state_20m.zip> [out.bin]

The output (default domain/src/main/resources/fuel/state-outlines.bin) is format v1, read by the app's
StateOutlines.decode. Every integer is an unsigned LEB128 varint unless noted:

    magic "GLSO" (4 ASCII bytes), version = 1, scale = 10000 (degrees x 10^4, rounded half away from zero),
    state count, then per state, sorted by postal code:
      postal code: 2 ASCII bytes
      bbox: minLon, minLat, maxLon, maxLat as zigzag varints (scaled)
      ring count
      per ring: point count, then (lon, lat) pairs as zigzag varint deltas from the previous point in the ring
      (the first point's delta is from 0,0)

Rings are written as the shapefile stores them, closing point included. The output is byte-for-byte deterministic:
it depends only on the zip's .shp and .dbf. Python 3 standard library only.
"""
from __future__ import annotations

import struct
import sys
import zipfile
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

MAGIC = b"GLSO"
VERSION = 1
SCALE = 10_000
POSTAL_FIELD = "STUSPS"
SHAPE_NULL = 0
SHAPE_POLYGON = 5
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "domain/src/main/resources/fuel/state-outlines.bin"


class OutlineError(Exception):
    pass


def read_dbf(data: bytes, field: str) -> list[str]:
    """The [field] column of a dBASE III table, one stripped value per record, in record order."""
    if len(data) < 32:
        raise OutlineError("dbf header truncated")
    count, header_len, record_len = struct.unpack_from("<IHH", data, 4)
    offset, position, found = 32, 1, None
    while offset < header_len - 1 and data[offset] != 0x0D:
        name = data[offset:offset + 11].split(b"\x00", 1)[0].decode("ascii")
        length = data[offset + 16]
        if name == field:
            found = (position, length)
        position += length
        offset += 32
    if found is None:
        raise OutlineError(f"dbf has no {field} field")
    start, length = found
    values = []
    for index in range(count):
        record = header_len + index * record_len
        if record + record_len > len(data):
            raise OutlineError("dbf record truncated")
        values.append(data[record + start:record + start + length].decode("ascii").strip())
    return values


def read_shp(data: bytes) -> list[list[list[tuple[float, float]]]]:
    """Each record's rings as (lon, lat) lists, in record order. A null shape has no rings."""
    if len(data) < 100 or struct.unpack_from(">i", data, 0)[0] != 9994:
        raise OutlineError("not a shapefile")
    shapes, offset = [], 100
    while offset < len(data):
        if offset + 8 > len(data):
            raise OutlineError("shp record header truncated")
        _, words = struct.unpack_from(">ii", data, offset)
        content, end = offset + 8, offset + 8 + words * 2
        if end > len(data):
            raise OutlineError("shp record truncated")
        shape_type = struct.unpack_from("<i", data, content)[0]
        if shape_type == SHAPE_NULL:
            shapes.append([])
        elif shape_type == SHAPE_POLYGON:
            parts_count, points_count = struct.unpack_from("<ii", data, content + 36)
            parts = list(struct.unpack_from(f"<{parts_count}i", data, content + 44))
            points_at = content + 44 + 4 * parts_count
            points = [struct.unpack_from("<2d", data, points_at + 16 * i) for i in range(points_count)]
            bounds = parts + [points_count]
            shapes.append([points[bounds[i]:bounds[i + 1]] for i in range(parts_count)])
        else:
            raise OutlineError(f"unsupported shape type {shape_type}")
        offset = end
    return shapes


def scaled(value: float) -> int:
    """[value] x SCALE, rounded half away from zero on the exact binary value (no float rounding drift)."""
    return int((Decimal(value) * SCALE).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def varint(value: int) -> bytes:
    if value < 0:
        raise OutlineError("varint is unsigned")
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def zigzag(value: int) -> bytes:
    return varint(value << 1 if value >= 0 else ((-value) << 1) - 1)


def encode(states: dict[str, list[list[tuple[float, float]]]]) -> bytes:
    """Format v1 of [states] (postal code -> rings of (lon, lat) degrees)."""
    out = bytearray(MAGIC)
    out += varint(VERSION) + varint(SCALE) + varint(len(states))
    for postal in sorted(states):
        if len(postal) != 2 or not postal.isascii() or not postal.isalpha() or not postal.isupper():
            raise OutlineError(f"bad postal code {postal!r}")
        rings = [[(scaled(lon), scaled(lat)) for lon, lat in ring] for ring in states[postal] if ring]
        if not rings:
            raise OutlineError(f"{postal} has no rings")
        points = [point for ring in rings for point in ring]
        out += postal.encode("ascii")
        for bound in (min(p[0] for p in points), min(p[1] for p in points),
                      max(p[0] for p in points), max(p[1] for p in points)):
            out += zigzag(bound)
        out += varint(len(rings))
        for ring in rings:
            out += varint(len(ring))
            last_lon, last_lat = 0, 0
            for lon, lat in ring:
                out += zigzag(lon - last_lon) + zigzag(lat - last_lat)
                last_lon, last_lat = lon, lat
    return bytes(out)


def states_from_zip(path: Path) -> dict[str, list[list[tuple[float, float]]]]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        shp = next((n for n in names if n.endswith(".shp")), None)
        dbf = next((n for n in names if n.endswith(".dbf")), None)
        if shp is None or dbf is None:
            raise OutlineError("zip has no .shp or .dbf")
        shapes = read_shp(archive.read(shp))
        postals = read_dbf(archive.read(dbf), POSTAL_FIELD)
    if len(shapes) != len(postals):
        raise OutlineError(f"{len(shapes)} shapes but {len(postals)} dbf records")
    states: dict[str, list[list[tuple[float, float]]]] = {}
    for postal, rings in zip(postals, shapes):
        if postal in states:
            raise OutlineError(f"duplicate postal code {postal}")
        states[postal] = rings
    return states


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    out = Path(argv[2]) if len(argv) == 3 else DEFAULT_OUT
    states = states_from_zip(Path(argv[1]))
    encoded = encode(states)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(encoded)
    points = sum(len(ring) for rings in states.values() for ring in rings)
    print(f"{out}: {len(states)} states, {points} points, {len(encoded)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
