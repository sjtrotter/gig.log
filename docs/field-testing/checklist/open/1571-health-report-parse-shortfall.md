status: active
issues: #1571

- **The health report carries per-rule shortfall counts (#1571).**
  With the census on, dash a busy day, then let the next daily run send that day's health report. Watch: the ops
  health view (`/ops/health`) shows the day's row for this install with a `parseShortfall` map naming at least one busy
  rule (an offer card or a delivery receipt) with a count from 0 to 20, and a rule that read fewer than 20 screens that
  day is absent from it. How to tell it works: on a day with no recognition notice, every count is low (most 0) and no
  `parse_shortfall` alarm fires for that day; the report holds rule ids and counts only, no screen text or value. A
  report that has no rule with 20 checked screens carries no `parseShortfall` key at all.
  - Issue: #1571. Confirmed: 0/2 (awaiting field validation)
