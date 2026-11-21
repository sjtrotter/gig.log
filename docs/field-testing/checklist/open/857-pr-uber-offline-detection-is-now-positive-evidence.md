status: review
issues: #857, #872, #874, #875

- **🆕 NEW — #857 / PR #872 — Uber offline detection is now positive-evidence (idle_map rewrite).**
  A partial render can no longer forge Online→Offline; a genuine offline home still recognizes via
  "You're offline" / "You're ready to go online" / the GO button; deliberate toggles ride the
  go_offline click. Deliberate bias: believe-online (a false offline destroyed sessions; a delayed
  offline costs seconds). **What to watch (Uber, especially multi-apping):** no more spurious
  "Started/Done Ubering!" churn while switching apps or gaming; exactly one session per real
  online/offline pair. **Desk:** `session_records` Uber session count == go_online/go_offline click
  pair count; every Online→Offline edge is ≤4 s from a go_offline click or a settled offline frame;
  known residuals #874 (home_dashboard absence arm) + #875 (the "Ready to go?" variant).
  - Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, Uber 4.606.10000): no phantom edge, seen working in the 11-20 pull, awaiting the developer's confirmation: both Online-to-Offline edges (19:30:03, 20:28:01) came from evidence-bearing frames. CONTRADICTS "deliberate toggles ride the go_offline click": the 12:02:39 `uber.click.go_offline` tap armed nothing, because a click never drives mode (`PlatformRegionStepper.kt`), and the shift stayed open 8 h 27 m. One Uber session against 3 go_online and 2 go_offline taps. Session log, bugs 1 and 2. Counter unchanged.
  desk: desk-verifiable: correlate Uber idle-map captures, explicit offline clicks and session boundaries to exclude phantom offline edges.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1437).
