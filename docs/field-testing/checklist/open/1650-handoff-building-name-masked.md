status: active
issues: #1650

- **The handoff screen masks the Building Name value (#1650):** on the next dash, find a recognized
  `dropoff_handoff` capture whose address block has a `Building Name` row. Working = the value after the label reads
  `[redacted]` (a value with a digit) or `[redacted:<4hex>]` (an all-letter name), and the label itself stays raw.
  A readable building name in that capture means the mask did not fire.
  - Confirmed: 0/2
