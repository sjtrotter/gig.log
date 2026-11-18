status: active
issues: #1668

- **The End dash tap ends a shift that earned nothing (#1668):** start a dash and end it before taking an offer, so DoorDash shows no dash summary. Tap End dash on the timeline, then End dash on the "End your current dash?" sheet. About ten seconds after that second tap the bubble leaves the live shift, without waiting for DoorDash's offline home, and the shift's end time is the tap, not when the home screen showed. Tapping End dash on the timeline and then Go back must not end the shift. The watch: if DoorDash shows an offer as you tap End dash, the shift keeps going (no end, no new shift); the shift then ends on the usual path once you really go offline. In the log: one `End-shift tap armed SESSION_END grace` line (DEBUG, tag `StateMachine`) per confirm tap, and `Non-authoritative SESSION_END cancelled by offer:presented screen` when an offer kept the shift going.
  - Confirmed: 0/2
