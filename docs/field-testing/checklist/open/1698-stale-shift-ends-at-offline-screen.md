status: active
issues: #1698

- **A stale Uber shift ends at Uber's offline screen (#1698):** leave an Uber shift open (go online, then leave Uber without a GO OFFLINE tap) and spend at least 30 minutes in another app with no Uber screen. Then open Uber: when it shows "You're offline", tap GO. The old Uber shift ends at the moment the offline screen appeared, and a new Uber shift starts at the first "Finding trips" screen, a few seconds later; Recap shows two Uber shifts, not one. The watch: a quick offline flash in the middle of an active Uber shift (Uber open, offers arriving) still keeps the same shift. In the log: one `Stale shift ends at offline screen … platform=uber` line (DEBUG, tag `StateMachine`) with the silence in seconds.
  - Confirmed: 0/2
