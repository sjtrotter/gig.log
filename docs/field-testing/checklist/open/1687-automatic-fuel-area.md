status: active
issues: #1687

- **The fuel price area picks itself from a shift's GPS fixes (#1687).** With Auto gas price on and Settings > Costs >
  Fuel price area on "Automatic (from your location)" (the default, or picked from the top of the list), run a shift.
  Working when: before the first shift the row reads "Automatic: U.S. until your first shift"; after the shift has
  driven a little, it reads "Automatic: <local area>" (your city if you dash within one of the 10 EIA cities, else
  your state, else your region), the gas price follows it with no refresh tap, and any "Using the <area> price" line
  names an area above it. An area picked by hand stays put through a whole shift. Broken = the row still on U.S. after
  a shift with fixes, the area of a state you did not drive in (beyond a few km from a state line), a hand-picked area
  changed by a shift, or a coordinate in any log line.
  - Confirmed: 0/2
