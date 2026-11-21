status: review
issues: #786, #869, #251

- **🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized).**
  The offer card's textless dismiss-X now latches `declineCommittedAt` → declines record as
  `OFFER_DECLINED` instead of the timeout ignorance-default. **What to watch (Uber):** the bubble's
  Dispatch line says "Offer Declined" on your X taps; **THE CRITICAL GUARD — an Uber dash with a
  REAL accepted, driven offer must produce `OFFER_ACCEPTED` + a costed job and NEVER a false
  `OFFER_DECLINED`** (the mislatch hazard the review closed with the own-text-empty predicate; if
  an accept ever records as declined, pull the build and report — that's the revert condition).
  **Desk:** `offer_records` Uber outcome census gains OFFER_DECLINED rows ≈ witnessed X taps
  (~claimed 4/5 in the 07-25 pull); unwitnessed disappearances stay TIMEOUT pending #251.
  - Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, Uber 4.606.10000): 18 of 18 X taps became `OFFER_DECLINED` and no accept was recorded as declined, seen working in the 11-20 pull, awaiting the developer's confirmation. One decline went to the wrong card: the 19:31:15 tap was booked to the previous offer because the whole-dollar "$10" card on screen was UNKNOWN (session log, bug 6). The one real accept was never seen (#826), so the critical guard is only half exercised. Counter unchanged.
  desk: needs field: obtain a real Uber accepted/driven offer and a dismiss-X decline, then check costed jobs and outcome events for false declines.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1447).
