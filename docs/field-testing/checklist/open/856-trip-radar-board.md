status: active
issues: #856, #251

- **Trip Radar board (#856):** after an Uber dash with Trip Radar open, no file under `captures/uber/accessibility.window/UNKNOWN/` carries a `<Street> & <Street>, <City>` line; the board's frames, including expired-card overlays ("This request is no longer available"), sort as `trip_radar_board` with `[redacted:…]` dropoff lines. Known residual, not a failure: the HOME-screen Trip Radar browse pill with a half-rendered card stays UNKNOWN (#856 / #251). — Confirmed: 0/2
  - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, Uber 4.606.10000): 3 board frames recognized as `trip_radar_board`, seen working in the 11-20 pull, awaiting the developer's confirmation. CONTRADICTS the UNKNOWN check as worded: three UNKNOWN offer-card partials (19:30:57 twice, 19:33:32) carry raw cross-street lines. They are offer cards, not board frames (session log, bug 10). Counter unchanged.
