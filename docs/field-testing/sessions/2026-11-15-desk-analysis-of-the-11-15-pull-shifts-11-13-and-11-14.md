## 2026-11-15 (desk analysis of the 11-15 pull: shifts 11-13 ×2 and 11-14; money reconciles; a stacked drop logged as arrived 5 mi early; the handoff screen saved a building name raw)

- **Date:** 2026-11-15 (pull). Shifts on 11-13 (two) and 11-14 (one, short, during the census upload test), DoorDash 8.107.10 (new since the last pull's 8.106.9). Uber 4.604/4.605, offline throughout.
- **Platform(s) tested:** DoorDash.
- **Branch under test:** `0.231.0+96fa4a89.dirty` (as in the 11-12 log) until 11-14 09:20; then `0.233.1+5e856841.dirty` (`master` at `5e8568412`, post-#1647). Not on the phone: #1648 and #1649 (#1568, the rules batch loader). After the pull the phone got `0.233.1+e6a934d4.dirty` (post-#1649); its startup logged `Rules generation live: 2 bundled + 0 remote file(s)` with no error (desk only, not a dash).
- **Field conditions:** three shifts. A 11-13 14:14–15:12, one offer accepted, one delivery, $18.20, ended at the offline home with no Dash summary. B 11-13 16:49–18:38, 2 of 2 offers, two jobs, four drops, $51.85. C 11-14 09:46–11:45, 1 of 6 offers accepted, one delivery, $43.18. Money reconciles to the cent on all three, and DoorDash's "This week" figure ($224.66) equals the 11-11 shifts plus these three.

### Bugs

1. **A stacked job's second drop was logged as arrived about 5 miles early (#1529, second sample).**
   - On job B1, drop 2's `DELIVERY_ARRIVED` landed 10 s after drop 1 was confirmed, at the same odometer reading. The trigger was a recognized `dropoff_handoff` frame ("Mark as delivered", "Directions", a due time) after a blank click and "Loading…" frames; `dropoff_navigation` followed 6 s later and the real door frames about 14 min later.
   - The row reads 0.0 drop miles (the shift's 5.02 mi gap between drop miles and the odometer span) and a 15.4 min dwell against about 1.5 min real, which also feeds #254.
   - One possibility: the drop sheet opens in the handoff layout when the next drop is opened, so "Mark as delivered" on screen is not by itself a door signal. Would need the frame sequence replayed to confirm.
   - **Status:** Open (sample added to #1529).
2. **The drop-off handoff screen saved a customer's building name unmasked (#1650).**
   - One recognized `dropoff_handoff` capture carried the address block's Building Name value raw; the same drop's `dropoff_pre_arrival` capture 11 s earlier masked it. The three sibling rules carry the #860/#1122/#1126 Building Name entries; `dropoff_handoff` does not, on master too, and no parity test pins it.
   - One possibility: DoorDash 8.107.10's handoff layout now renders the address block. Nothing left the phone (census sends hash-only skeletons). The capture was deleted from the pull.
   - **Status:** Filed as #1650; a fix is in progress.
3. **No Dash summary after End dash on shift A.** The End dash confirm went straight to the offline home; the other two shifts showed the summary. The shift ended as `early_offline` and its money is right. It was ended from the timeline right after a Dash Along the Way delivery with no receipt sheet. Cause unknown; relevant to #1459 and #1527.
4. **The gas-price fetch fails with HTTP 403 on every attempt, on both builds** (every 5 h since at least 11-06). Fuel costs run on a stored or fallback price. One possibility: the CI-built APK carries no EIA key (the key comes from `local.properties`), but the local build fails too; a desk call with the real key hung rather than answering. Related to #1566 (the key leaves the app) and #1478.
   - **Status:** Open, unconfirmed.
5. **Two-order shop card read as a quick pickup on the old build (#1528).** "Shop for 2 orders (23 items, 25 units)" stored 0 items and a 28.7 min estimate; the job took 69 min. #1595 shipped after that build; the "units" wording is still untested. On 0.233.1 a single-order "66 items (82 units)" card read correctly.

### Working as intended

- **#1604:** first positive. A "$37.25+" card with a High pay for Gold badge was recorded, accepted at $37.25 and linked.
- **#1598:** one receipt, PROVEN_SINGLE, paid in full. Shift B's stacked receipts were split evenly on the old build; today they would be unproven (card count not one) and wait for #1601.
- **#1119 / #1610 offer pool (shift C):** five sheet declines demoted to `OFFER_DECLINED` (`SHEET_WINDOW`), one accept linked by store, no tie, no timeout.
- **#1458 data half:** both stacked jobs committed seconds after their last confirm; no `left open` line (#1611 did not recur).
- **#919, #1310, #1311, #1396, #1560:** consistent; see the checklist items. No counter changed.
- **Census device side:** about 114 items uploaded on 11-13 in 24 batches, 0 rejected; one 4-item upload on 11-14 (the manual run). Notification skeletons are refused with `POLICY_UNSUPPORTED_SCHEMA`: the deployed server (v0.15.0) advertises no notification schema, which keeps #1189 unexercised.

### Privacy sweep (new captures)

- Clean: no `For Firstname L.` shape, no raw `Deliver to`/`Pickup for`, no PIN or gate code, no DasherDirect or banking text. Street-address hits are store addresses. Every UNKNOWN name and address field is masked.
- Open: customer-written item notes raw in `pickup_shopping` body text (the #803/#920 class, no name, address or code); the order-number title on `pickup_pre_arrival` (open question 4 of the 11-12 log); road names from turn-by-turn directions on pickup-leg click UNKNOWNs (the dasher's own route to a store; drop-leg taps are masked).

### New unruled surfaces

- "A new order is added to your shopping list / You're shopping for 2 separate orders".
- "Verification still in progress … Enter code" at a store's QR checkout.
- DoorDash's weekly earnings page ("Active time").
- A title-only "New Delivery!" push; three more zone-promo push variants (a known class).

### Verification TODOs

- The bubble headline on the stacked jobs and the shift A end label need the developer's eyes.
- #1568 on a dash: recognition unchanged and one `Rules generation live:` line per start.
