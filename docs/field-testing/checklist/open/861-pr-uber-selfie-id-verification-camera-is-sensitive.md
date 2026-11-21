status: review
issues: #861, #868

- **🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked.**
  **Desk only:** next pull has ZERO selfie-flow UNKNOWN envelopes (`facecamera` ids /
  "Fit your face in the guide"); `sensitiveDropped` increments across that window instead.
  - Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, Uber 4.606.10000): 19 `sensitive.id_verification` drops across the 12:00:52 selfie flow and no selfie-flow UNKNOWN envelope, seen working in the 11-20 pull, awaiting the developer's confirmation. Counter unchanged.
  desk: desk-verifiable: correlate an exercised selfie-flow window with sensitiveDropped and absence of facecamera/guide envelopes.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1464).
