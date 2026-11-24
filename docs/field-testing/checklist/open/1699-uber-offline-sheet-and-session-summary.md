status: active
issues: #1699

- **Uber's offline status sheet and "Your session" sheet are recognized (#1699):** go online in Uber, then tap GO OFFLINE and stay in Uber. The full-map status sheet that follows may read "You're offline", a promo line (for example "It's dinner time" over "Check map for busy areas.") or "Before you go online" with a required action; whichever it reads, the Uber shift ends there without waiting for the inset home. Open the "Your session (<start> - <end>)" sheet afterwards: it is recognized too. The watch: no online frame ("Finding trips", "You're online", a trip card) ever ends the shift. In the log: the sheet's frame is `SCREEN: idle_map` (VERBOSE, tag `Classifier`) and the session sheet is `SCREEN: session_summary`; on the debug build neither leaves a new UNKNOWN capture under `captures/uber/accessibility.window/`.
  - Confirmed: 0/2
