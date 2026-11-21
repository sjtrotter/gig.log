status: review
issues: #874, #875, #857, #872

- **🆕 NEW — #874 / #875 — the Uber HOME screen stopped guessing "offline", and the nav-less
  offline card is now recognized.** Sibling of #857/PR #872, one rule down: `home_dashboard`
  branch 3 claimed offline whenever the bottom nav existed and the words "You're online" did not
  — so a half-drawn online dashboard, or a *Trip Details* screen stacked over it, forged the same
  destructive Online→Offline edge. It now requires the offline card's own headline
  (`header_text_view` = "You're offline"), which also recognizes the fielded card-without-nav
  variant (#875) that previously landed UNKNOWN.
  **What to watch (Uber dash, multi-apping):** no phantom "Done Ubering!"/"Started Ubering!" churn
  while you're bouncing between apps; the dash-count at the end of the session still equals the
  number of times you actually pressed GO / went offline; and going offline from the home screen
  is still *noticed* (the HUD should flip within a frame or two of the card appearing, not stay
  stuck Online for the rest of the dash).
  **Desk (next pull):** in `captures/uber/accessibility.window/`, frames now folder-ing as
  `home_dashboard` should each carry either `go_online_button` or `header_text_view` =
  "You're offline"; the 68–69-node nav-bar-only frames and any "Trip Details" frame should land in
  `UNKNOWN` (that is the fix, not a regression). Online→Offline edges in `app_events` should be
  1:1 with `uber.click.go_offline` clicks or with an evidence-bearing home/idle_map frame.
  - Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, Uber 4.606.10000): every `home_dashboard` frame carried `go_online_button` and the nav-bar-only frame (19:30:03.760) stayed UNKNOWN, seen working in the 11-20 pull, awaiting the developer's confirmation. The offline home at 19:30:03 armed the end of a shift left open since 12:02; going online 7 s later cancelled it (session log, bug 2). The "Unable to go online" selfie card stays UNKNOWN (no session was open). The premise that the go_offline click covers deliberate toggles does not hold: the click moves nothing (session log, bug 1). Counter unchanged.
  desk: desk-verifiable: correlate Uber home captures and offline events with positive offline evidence; nav-only frames must not end a session.
  Triage source: first-line blame 2026-07-27 (`81f5eb304`, original README L1199).
