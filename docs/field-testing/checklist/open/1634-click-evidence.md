status: active
issues: #1634

- **An unrecognized tap shows on the operator's tap view the next day; a tap on a blocked screen never does (#1634).**
  Needs the census server that lists `uinode.clicked.v1` (census#87 deployed), this install trusted on it, and the
  census accepted again on this build (a consent from before PRIVACY.md r33 sends no tap). On a shift, tap a button
  gig.log does not recognize (a menu entry, a help link) a few times, and open a banking or DasherDirect screen and tap
  a button there. Watch, in the `PipelineStats` INFO summary: a ` clicked{offered=…}` part that grows with
  unrecognized taps, `refused{BLOCKED=…}` (or `SENSITIVE`) for the blocked-screen taps, no `ACQUIRE_FAILED` or
  `NO_WINDOW` on ordinary taps, and only `UNARMED` while the census is off. The day after (the batch goes only once the
  UTC day has ended), the operator's tap view (`/ops/taps`, unblinded for a trusted install) lists the tapped buttons
  by owner path with their class and id, and none from the blocked screen. How to tell it works: taps, the bubble
  and recognition behave as before; recognized taps (Accept, Decline) never appear; at most 10 taps go a day.
  - Issue: #1634. Confirmed: 0/2 (awaiting field validation)
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`): not on the phone; the build predates PRs #1694 and #1696. Counter unchanged.
