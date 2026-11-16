status: active
issues: #1630

- **Shadow mode runs the check candidate and reports it daily (#1630).**
  Install a build with the shadow check candidate (`matchers/rules/doordash/shadow-check.json5`, a shadow copy of
  `waiting_for_offer`), turn the census on and dash with some waiting for offers. Watch, in the `PipelineStats` INFO
  summary: a ` shadow{...}` part with `frames` rising during the dash, `matched` above zero, `budget=0` and `failed=0`,
  `dropped` at or near zero, and `medianMs` under a few ms. The next day's health report for that row (`/ops/health`)
  carries one `shadowDaily` entry for `doordash.screen.waiting_for_offer` with `visits` above `"0"`, `tied: "all"`,
  `onUnknown: "none"`, and no `shadowDailyTruncated`. How to tell it works: recognition behaves as before (same screens
  recognized, no new lag in the bubble), the entry holds ids and levels only, and a day with no waiting for offers lists
  the candidate with `visits: "0"` and no metrics, or not at all when the DoorDash row was never written that day.
  - Issue: #1630. Confirmed: 0/2 (awaiting field validation)
