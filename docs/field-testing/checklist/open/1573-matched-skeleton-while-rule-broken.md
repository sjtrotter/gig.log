status: active
issues: #1573

- **A rule in a parse-rot incident sends its screen's outline once a day (#1573).**
  Needs the census server that lists `uinode.matched.v1` (census#69 deployed) and the census accepted again on this
  build (a consent from before PRIVACY.md r32 sends no matched outline). Only a real parse-rot incident triggers it:
  a delivery receipt, an end-of-shift summary or an offer card whose pay or distance stops reading, five visits in a
  row. Watch, in the `PipelineStats` INFO summary: a ` matched{held=…,released=…,dropped=…}` part that appears only
  after the incident notice (`released` counts offers to the census queue, which then keeps at most 1 per rule and 10
  a day: check that on the operator's view, not on `released`), and no `refused{…}` beyond
  `POLICY_UNSUPPORTED_SCHEMA` on a phone whose server or consent does not list it yet. After the next daily upload, the
  operator's per-rule view (`/ops/rules/view`) lists the rule with one sample for that day, its money slots read as
  `shape:$#.##`, and the customer slots the rule redacts read `withheld`. How to tell it works: nothing changes in
  recognition or the bubble, no matched outline is sent for a healthy rule or with the census off, and the UNKNOWN
  census counts (` census{…}`) are what they were.
  - Issue: #1573. Confirmed: 0/2 (awaiting field validation)
