status: active
issues: #1669

- **The waiting-for-offers screen is recognized without its "Finding offers" card (#1669):** on DoorDash 8.107.10 or later, during a dash, watch the screen between offers. DoorDash often draws it with only the "Dash Preferences" / "Safety tools" / "Open Settings" / "Help" buttons and the "This dash" pill, without the "Finding offers" card. gig.log should treat that screen as online and waiting, and the bubble's running total should follow the "This dash" pill (it moves after the pill has shown the new figure for about 3 s). How to tell it works: after an offer is delivered, the running total updates on this screen without the card; it never shows the "Weekly goal" percent the pill sometimes shows.
  - Confirmed: 0/2
