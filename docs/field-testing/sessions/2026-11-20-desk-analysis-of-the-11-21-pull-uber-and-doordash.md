## 2026-11-20 (desk analysis of the 11-21 pull: two DoorDash shifts and an Uber evening; the Uber shift stayed open 8 h 27 m after a GO OFFLINE tap; the one Uber delivery recorded $0; DoorDash short $34.23)

- **Date:** 2026-11-20 (shifts), pulled 2026-11-21.
- **Platform(s) tested:** DoorDash and Uber, multi-apping at noon.
- **Branch under test:** `0.233.1+c4ada68c` on every `PipelineStats` line (startup 2026-11-19 15:49). Not on the phone: the 37 merges after `c4ada68c`, including #1634 click evidence (PRs #1694 and #1696), so nothing here tests #1634. Platform apps: DoorDash 8.108.11, Uber 4.606.10000. Master at the time of this log: `131860074`.
- **Field conditions:**
  - **DoorDash shift 1**, 11:31 to 14:10: 1 of 7 offers accepted, DoorDash reported $59.23, the app recorded $33.50.
  - **Uber at noon**, 12:00 to 12:02: the selfie check, then 7 offers declined, then GO OFFLINE. The dasher switched to DoorDash 3 s after the tap. The app saw no Uber window for the next 7.5 h.
  - **DoorDash shift 2**, 16:58 to 19:26: 4 accepts (3 jobs and an add-on), DoorDash reported $71.60, the app recorded $63.10.
  - **Uber in the evening**, 19:30 to 20:28: 12 offers by Uber's own session sheet, one delivery at a fast-food store, $20.09 on Uber's screens ($11.41 fare and $8.68 tip). The app recorded $0.
  - Both DoorDash shifts ended on the dash summary after the End dash confirm tap.

### Bugs

1. **The Uber shift stayed open 8 h 27 m after a GO OFFLINE tap.**
   - The 12:02:39 tap was recognized (`uber.click.go_offline`, mode hint Offline). The two Uber frames after it, 0.6 s and 5.2 s later, still read "You're online". The dasher left Uber at 12:02:42 and the app saw no Uber window until 19:30:00, so the shift kept running from 12:01:17 to 20:28:01.
   - Likely cause (lead-verified in code): a click never drives mode. `PlatformRegionStepper.kt` reads "Click — records user intent, does NOT drive mode", so the go_offline click arms no end. The rule comments in `matchers/rules/uber.json5` (the #857 `idle_map` comment and the #874 `home_dashboard` comment) assume deliberate toggles "ride the go_offline click". The code does not do that.
   - At 20:28 the end worked because the "You're offline" frame came 1.1 s after the tap and the dasher stayed on it. At 12:02 the dasher switched apps before that frame could come.
   - Evidence that Uber really went offline at 12:02: no Uber offer for 7.5 h, against one every 10 to 30 s in the 19:30 burst, and Uber showed its offline home at 19:30:03.
   - **Status:** Open (#1697).
2. **Going online after the long gap cancelled the stale shift's end instead of ending it.**
   - At 19:30:03 the inset offline home armed the stale shift's end. The GO tap came at 19:30:06 and the "Finding trips" sheet at 19:30:10; an online frame cancels an end armed by an Offline edge, so the real evening shift merged into the noon one.
   - Miles: the merged Uber row claims 83.45 mi. About 57 mi of it (16.65 and 40.62) is the same driving as the two DoorDash shifts, 12.93 mi is off-shift driving between 14:10 and 16:58, and about 13 mi is Uber work. The developer's own odometer readings in that gap show 12 mi against 11.40 GPS miles, so the GPS was accurate and the booking was wrong. The overlap half is #1507's double count; the off-shift half comes from bug 1.
   - One possibility: an Offline-edge end standing after a long stretch with no frame from that platform is evidence the old shift ended, and a resume should commit it at its last evidence (12:02:45 here) before a new shift starts.
   - **Status:** Open (#1698).
3. **The one Uber delivery was not recorded (#826).** The $20.09 offer was presented at 19:33:58 and 19:34:08; no accept click reached the app, not even a textless one. `active_trip` names no store, so the #1119 pool held the store-less work frame and then minted a bare job with no tasks at 19:34:28. Four offers from the same fast-food store sat in the pool (two at $6.54, two presentations of the $20.09). No delivery-confirmation or post-trip screen was recognized. At shift end the accepted offer ended `OFFER_NOT_TAKEN` and the Uber row shows $0. The "Going to <store>" and "Arrived at <store>" pushes carry the store name, but `trip_en_route_pickup` parses no `storeName`.
   - **Status:** Open (#826, #1600). Evidence added to #826.
4. **Uber pay notifications are not read, and two pushes after the trip were dropped without a trace.**
   - The tip push at 20:28:06 ("You've received a $8.68 tip!") matched `uber.notification.tip_received`, which has no amount parse. Nothing reached the delivery or a receipt row.
   - Lead-verified finding: two Uber notifications after the trip, one a fraction of a second after the tip push (20:28:06.434) and one at 20:47:47, were admitted by the listener and then dropped. Either the duplicate gate or the sensitive/noise content gate dropped them (`NotificationPipeline.kt`), and nothing logs which gate, nor the text. The evening's `PipelineStats` summary lines stop at 14:09, so the counters cannot say either.
   - Hypothesis: those two were the developer's ~$11 fare and ~$3 pushes (developer observation 3). Against it: the desk report read the 20:28:06.434 one as a repost of the tip push, and Uber's own screens at 20:51 show $20.09 with no $3 line.
   - The $8.68 tip is already inside the offer's "Guaranteed (incl. tip)" $20.09, so adding a tip push on top would count it twice.
   - **Status:** Open (#1702).
5. **"Must verify ID" was read as the store.** On two Uber offers (12:01:22, a liquor store; 19:32:26, a big-box store) the ID-check chip won `storeName` in `uber.screen.offer`, in both the display copy and the `orders[]` copy. The voice said "Offer. Must verify ID.", the chat line and `offer_records.merchantName` say the same, and both offers share presentation key `7e728b89…`, a false-merge risk under the #1069 identity rule. The chip sits above the store line and is not on the exclusion list. The Trip Radar board rule already lists it as chrome.
   - **Status:** Open (#1700).
6. **A whole-dollar Uber offer ("$10") fell to UNKNOWN, and its X tap was booked to the card before it.** A restaurant offer at $10 was on screen 19:30:57 to 19:31:13. The offer rule requires `\$\d+\.\d{2}`, so all three of its frames went UNKNOWN. The X tap at 19:31:15 was recorded as a decline of the previous card (a pharmacy offer, $8.37).
   - **Status:** Open (#1701).
7. **Two Uber offline screens are unrecognized.** At 20:28:52 the full-map status sheet read a promo line ("It's dinner time") where "You're offline" sits, with no GO button, so `idle_map` fails all three of its positive arms. At 20:28:04 the "Your session (7:30 PM - 8:28 PM)" summary sheet (pay, trips completed, trips offered) went UNKNOWN because `uber.screen.session_summary` requires "Session summary". Candidate anchor for the first: `agenda_feed_content_view` beside `status_assistant`, present on 4 of 4 offline sheet frames (2 here, 2 in the corpus) and on none of 42 online, on-trip or splash frames. Four is a thin sample.
   - **Status:** Open (#1699).
8. **DoorDash shift 1 is short $25.73: a stale pooled offer tied with the real accept.** A 12:01 grocery offer ran out its countdown while the dasher was declining Uber offers, so DoorDash's decline sheet was never seen and #1610's demotion did not apply. It stayed a candidate. At 13:11 the dasher accepted an offer at the same chain; it re-rendered as UNKNOWN frames without Accept and left by the expiry timer. At 13:12:21 the two tied (`#1119 2 pooled offers tie`). The receipt ($25.73) stayed unproven (`OFFER_COUNT`) and placed nothing, as #1598 intends.
   - **Status:** Open (#1704).
9. **DoorDash shift 2 is short $8.50: an add-on's drop merged into another drop.** A three-store job (two offers plus an add-on accepted at 18:56:42) had three customers but two delivery rows. One drop navigated and arrived as the add-on's store and customer, then confirmed and completed as another store's. Recorded $20.75 against $29.25 of offers. `#1458 job … left open … outstanding=1` at 19:26:05; #997 `STAMP_FALLBACK`.
   - **Status:** Open (#1600, #1489). Evidence added to #1600.
10. **Raw customer PII in UNKNOWN Uber debug captures.** A customer's first name and last initial inside a tapped order-card button (click at 19:36:06), a customer's drop-off street and city on three Trip Details frames (20:47:51, 20:47:57, 20:47:59), and cross-street lines on three offer-card partials (19:30:57 twice, 19:33:32). Debug captures only. Lead-verified: the census received layouts and hashed text only; its cluster JSON holds no text fields.
    - **Status:** Open (#1703).

### Developer observations

1. Uber has two offline map screens: the map still full-screen but offline, and the map inset into the page like the home. The pull matches: the full-map sheet reading "You're offline" came right after the 20:28 tap, and the inset home about 55 s later.
2. The dasher can dismiss Uber's ongoing "online" notification. A removal signal must tell a removal by the app from one by the user. Android's `onNotificationRemoved` reason codes make the split: app cancel against user cancel, clear-all or click. Today `NotificationListener.onNotificationRemoved` is empty, so no removal is seen (#249 covers the posted side only). The ongoing notification was posted at 12:01:06 and again at 19:30:08.
3. The Uber pay arrived as three notifications: about $11, then $8, then $3. Only the $8.68 tip push was recognized (bug 4).

### Working as intended

- **#1668:** both DoorDash End dash confirm taps armed `SESSION_END` (10 s grace) at the tap; the summary tightened it and the commit came about 5 s later, with the end time at the tap. Neither was the no-summary case.
- **#786:** 18 of 18 Uber X taps became `OFFER_DECLINED` (one booked to the wrong card, bug 6). No accept was recorded as a decline.
- **DoorDash declines:** 22 of 22 went through the confirm sheet, matching the shift-end INFO lines; 7 of them through the bubble or heads-up.
- **#857 / #874:** both Uber Online-to-Offline edges came from evidence-bearing frames; no phantom edge.
- **#861:** the selfie camera was blocked (19 `sensitive.id_verification` drops); nothing captured. The Uber wallet section was blocked 6 times.
- **#1152:** `overlaySnapshots=84`; Uber offers were read from the overlay.
- **#856:** 3 Trip Radar board frames recognized.
- **#1598:** one PROVEN_SINGLE receipt placed its whole total; three UNPROVEN receipts placed nothing.
- **#1601:** the two-drop one-offer job settled from its receipt (`settled r1`).
- **#1672:** 4 `Performing expand_earnings` lines for 4 receipts, plus one dropped repeat.
- **#688B / #1108 miles:** drop miles within the odometer span on both DoorDash shifts (25.89 of 31.72, 38.65 of 40.62).
- **WARN/ERROR census (11-17 to 11-21):** no ERROR. WARNs: 11 GRACE_COMMIT, 10 capture-backstop PII masks (the backstop working), 7 odometer fix rejections, 6 parse shortfalls (`waiting_for_offer`, `idle_map`, `dash_along_the_way`, `pickup_shopping`), the one #1119 tie, one #691 unsplit, one `NoTrustedEpoch` (11-19), and the 200 UNKNOWN capture cap reached at 20:51.
- **Census device side:** uploads on 11-17, 11-19, 11-20 and 11-21, 0 rejected. Server half not read.

### Field UX context

- **Uber 4.606:** `uber.click.go_offline` still matches (`carbon_action_button` inside `go_offline_view`). The selfie check blocks going online until done ("Unable to go online / Required actions (1)", UNKNOWN, no session open). `status_assistant_overlay_text` = "Unable to go offline" sits as hidden chrome on every status sheet and is not a state signal.
- **Uber pay surfaces:** the offer card says "Guaranteed (incl. tip)"; Trip Details (per trip) and the Earnings tab (Net Fare, Tip, Total) state the totals and are UNKNOWN today. Trip Details carries the customer's drop-off line beside the money.
- **Four Uber offers left with no tap seen** (8 to 10 s each): hypothesis, they expired or went away by a gesture the app does not see.
- **DoorDash 8.108.11:** the button-less offline home and side-menu family is still UNKNOWN (about 36 frames); about 27 offer pop-up frames without Accept (the #1063 shape), one of them the accepted 13:11 offer in bug 8.

### Privacy sweep (new captures)

- Dasher banking: zero hits in captures (DasherDirect 14 and Crimson 2 screens blocked at the gate). The two `earnings_deposit` pushes are fully redacted.
- Customer PII: bug 10. Low severity: DoorDash in-app navigation street names on pickup-leg frames, no house number.
- No `For Firstname L.` shape, `Pickup for`, `Deliver to`, PIN, gate code or Customer Notes value.
- Packages: only DoorDash and Uber appear. A Pro Card (debit card) app is not a platform package; `NotificationListener` drops it before logging, so its notifications are never seen, and as a dasher-banking app they must stay unread.

### Open questions

- Should Uber's go_offline tap arm a graced end the way DoorDash's End dash confirm does, and what cancels it if Uber refuses to go offline? (bug 1)
- Is a long stretch with no frame from a platform, ended by that platform's offline home, enough evidence to end the old shift rather than cancel into it? (bug 2)
- Which gate dropped the 20:28:06.434 and 20:47:47 Uber pushes, and what did they say? A DEBUG line naming the gate and the rule id, with no notification text, would answer it next time. (bug 4)
- Did the ~$3 come from Uber, from a support adjustment after the 21:17 survey, or from the Pro Card app? (developer observation 3)
- Why did the inherited `active_trip` redact miss the name inside a non-leaf clicked button? (bug 10)

### Verification TODOs

- Purge the seven Uber captures named in bug 10 from the pull and the device once nothing needs them.
- Read the census server's Uber clusters for the Trip Details and order-card shapes (tunnel was down).
- Next Uber shift: go offline and stay on the offline screen 10 s, then once switch apps right after the tap; compare the two shift ends.
