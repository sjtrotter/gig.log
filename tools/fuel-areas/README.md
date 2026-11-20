# State outlines for the automatic fuel price area

`domain/src/main/resources/fuel/state-outlines.bin` holds simplified outlines of the 50 states, DC and Puerto Rico.
The app's `FuelAreaLocator` checks a GPS fix against them on the phone to pick the gas price area (#1687). The fix is
never sent anywhere and never stored; only the resulting area code is.

## Source

- File: `cb_2024_us_state_20m.zip`, from
  <https://www2.census.gov/geo/tiger/GENZ2024/shp/cb_2024_us_state_20m.zip>
- Vintage: 2024 cartographic boundary file, 1:20,000,000 scale
- sha256 of the zip: `7bc773d83c01b6df69b8aada9c2b5983d97f22f4551947cc5c00b87c663223ef`
- Licence: U.S. Census Bureau cartographic boundary files are a work of the U.S. federal government and in the public
  domain.

The zip and its shapefile are not committed. The generated `.bin` is: 52 records, 13,706 points, 50,874 bytes.

## Regenerate

```bash
python3 -I tools/fuel-areas/gen_state_outlines.py /path/to/cb_2024_us_state_20m.zip
python3 -I -m unittest discover -s tools/fuel-areas -p 'test_*.py'
```

The generator uses the Python standard library only, and its output is byte-for-byte deterministic. The format is
described at the top of `gen_state_outlines.py` and read by `StateOutlines.decode` (`:domain`, `fuel` package).

## Accuracy

The 1:20,000,000 simplification moves state lines by up to a few km, so a fix within a few km of a state line can be
placed in the neighbouring state. Coordinates are rounded to 10^-4 degrees (about 11 m), far below that error.
