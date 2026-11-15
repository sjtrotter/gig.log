status: active
issues: #640

- **Recognition rules: the notice actions and Check now (#640).**
  Settings > Recognition rules shows "Keep recognition rules up to date" off on a fresh install, with the text that
  GitHub and Fastly can tell when this phone opens a delivery app. With it off, the rules in use stay (Settings shows
  "Built in" or the date of the last download) and opening DoorDash sends nothing: no `rules generation live` or
  `feed` line in the log. Tapping Check now shows "Checking", then a "Last check" line with the time and "new rules in
  use", "no update" or "couldn't check"; the switch stays off. With the switch on, opening a delivery app, or coming
  back to it after 10 minutes or more away, checks at most once an hour; using the app without a break never checks
  again (desk: `grep -h 'feed=rules' app.log` shows at most one `feed accepted`, `feed kept last-good` or `feed fetch
  failed` line per hour of foregrounds; until #641 pins the rules key every check is `feed kept last-good ...
  NoTrustedEpoch`, with no request sent). On a recognition notice, once a rules key is pinned: "Check for new rules"
  replaces the notice with "New recognition rules in use", "Couldn't check for new rules" or "No update yet for
  DoorDash", and a second tap that finds no update posts nothing new for the same incident; "Keep rules up to date"
  opens Settings > Recognition rules with the switch still off (the dasher turns it on there, beside the disclosure),
  and is not offered while the switch is on. Until #641 pins a key the notice shows no rules buttons.
  - Issue: #640. Confirmed: 0/2 (awaiting field validation)
