status: active
issues: #1697

- **Uber's GO OFFLINE tap ends the Uber shift (#1697):** go online in Uber, then tap GO OFFLINE on the "Finding trips" sheet and switch straight to another app before Uber draws "You're offline". About ten seconds after the tap the Uber shift ends, its end time is the tap, and with no other shift open the mileage notification goes away. The watch: if an Uber offer arrives within those ten seconds, the shift keeps going. A tap on any other Uber button (during a trip, on the splash screen) must not end the shift. In the log: one `End-shift tap armed SESSION_END grace … platform=uber` line (DEBUG, tag `StateMachine`) per GO OFFLINE tap, and `Tap-armed SESSION_END cancelled by offer:presented screen` when an offer kept the shift going.
  - Confirmed: 0/2
