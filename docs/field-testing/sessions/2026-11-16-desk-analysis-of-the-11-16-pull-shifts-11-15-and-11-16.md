## 2026-11-16 (desk analysis of the 11-16 pull: shifts 11-15 ×2 and 11-16; a same-store add-on accept recorded as not taken; DoorDash 8.107.10 broke no state-bearing screen; the app's own expand re-opened dismissed receipts)

- **Date:** 2026-11-16 (pull). Shifts on 11-15 (two) and 11-16 (one, short, no offers). DoorDash 8.107.10 on every frame (446 captures). No Uber activity.
- **Platform(s) tested:** DoorDash.
- **Branch under test:** `0.233.1+7a193657` (`master` at `7a193657`, post-#1659, clean tree) for every shift. Not on the phone during them: #1662 and #1664–#1667 (census shadow contract, #1630 shadow mode). After the pull the session installed `0.233.1+6b740ee6` (shadow mode) on 11-16 and `0.233.1+48c96793` (post-#1674, the #1672 fix) on 11-17; `dumpsys package` read `versionName=0.233.1+48c96793` after the second install.
- **Field conditions:** three shifts.
  - **S1**, 11-15 14:14–16:42: 3 of 5 offers accepted, three single-drop jobs, $65.98, ended on the dash summary. Money reconciles to the cent.
  - **S2**, 11-15 17:47–19:19: DoorDash counts 3 of 6 accepted, the app 2. A two-drop job with a same-store add-on. $42.03 reported. The recorded rows reach it only through a $5.00 delivery the dasher added by hand after the shift (bug 1).
  - **S3**, 11-16 13:36–13:43: no offers, ended at the offline home with no dash summary (`early_offline`).
  - The week's sessions (Mon–Sun) sum to DoorDash's own week total on its earnings page. DoorDash counts 19 completed deliveries; the app holds 20 rows, the extra being the hand-added $5.00 row for an order that already has its drop row.

### Bugs

1. **A same-store add-on accepted at the store was recorded as not taken (#1600).**
   - S2, 18:23: an add-on card (+$5.00, +1 stop, the same chain as the job in progress) was recognized as an add-on (incremental basis) and spoken once. When the card left, the app pooled it as a possible missed accept; the link then found "no pooled offer at this store". At shift end it was recorded `OFFER_NOT_TAKEN`. Compose cards give no accept click, by design.
   - One possibility, from reading `OfferPool.candidateStoreKeys`: for an add-on card presented over a live job the pool keys only stores new to that job, so a same-store add-on has no candidate key. The pickup task kept its identity and a second drop appeared under the same job, so nothing on the pool path ever sees "new work".
   - Knock-on: the job's two drops split the original offer's pay evenly. DoorDash's running totals and the receipt suggest the first drop was the $5.00 add-on order.
   - On-screen evidence that exists (one sighting, not a design): the next `pickup_pre_arrival` frame names the add-on's pay, a new deadline, a new item count and another customer hash; the shopping total rose by the add-on's item count; a "A new order is added to your shopping list" sheet appeared (UNKNOWN). Other add-on kinds (another store, a food pickup) still need captures.
   - **Status:** evidence posted on #1600 (explore, not definitive).
2. **The app re-opened receipts the dasher had dismissed (#1672).**
   - S2, 19:18: the app expanded the receipt; each time the dasher dismissed it, DoorDash drew it collapsed for a moment before the "How was this task" prompt, the app armed a fresh expand and the receipt re-opened (twice in 18 s). The dasher expands no receipt by hand: every expand in the pull was the app's tap, and the `accessibility.click` UNKNOWNs on the Expand control are those taps echoed.
   - **Status:** fixed by #1674 (one automatic expand per receipt episode); field item `1672-dismissed-receipt-stays-dismissed` at 0/2.
3. **A false recognition notice on a normal shift (#1560).** S2, 17:50: "80% of the last 50 frames UNKNOWN" posted while the dasher browsed DoorDash's earnings pages between shifts and the idle and waiting screens rendered without their anchors. No state-bearing screen failed in that window. One possibility: the ratio counts offline browsing; the #1669 and #1670 re-anchors remove most of these frames.
   - **Status:** open (evidence on the item).
4. **S3's end was recorded about 11 s after End dash (#1527, #1668).** The offline home drew under a workflow host without its Dash button for 9 s; `idle_map` first matched at 13:43:15. PR #1675 recognizes the button-less home by its "Avg. offer wait" stat, but the frames right after End dash carry only chrome the online home shares and stay UNKNOWN.
   - **Status:** #1668 open for the late end (one possibility: the End dash confirm click).
5. **A lone street line in an UNKNOWN capture (#1671).** The customer's drop-off street, drawn as a map label on the offer card in the frame caught at the accept; it matches the mask on that delivery's drop-off screen. The census withheld it (a street-shape value); the local debug capture kept it because the address backstop claims only a street plus a city line. The capture was deleted from the pull; the phone's copy went with the post-pull purge of every capture on the device (verified 0 files left).
   - **Status:** filed as #1671.
6. **Gas price:** the HTTP 403s seen since 11-06 happened only on the two `.dirty` builds; the clean `7a193657` build fetched the price. One possibility: the key differs between local dirty builds and this one. Unconfirmed.
7. **Rules feed:** four `NoTrustedEpoch` WARNs within 38 s right after install, consistent with Check now taps. Unconfirmed.

### Working as intended

- **#1598:** three receipts; two proven single and placed in full, one unproven (the two-drop job) placed nothing and its drops keep offer-pay halves.
- **#1610:** S2 was one chain throughout, with no tie; two links each named a card presented after the declined one left.
- **#1458:** S2's two-drop job committed 9 s after the last photo; no `left open` line.
- **#688B / #1108 mileage:** drop miles match the odometer span within 0.02 mi on both shifts.
- **#919, #1058, #1311:** consistent; no counter changed.
- **Census device side:** four uploads, 0 rejected; notification skeletons still refused (`POLICY_UNSUPPORTED_SCHEMA`, #1189 unexercised).

### DoorDash 8.107.10

- No state-bearing screen fell to UNKNOWN: every offer, pickup arrival and confirm, drop arrival and confirm, shown receipt and both dash summaries were recognized.
- What changed: the offline home and the waiting-for-offer screen render without their anchor controls far more often (#1668, #1669, re-anchored in #1675), and a new offer item-list sheet exists (#1047). The earnings pages had lost their anchor text (#1670, re-anchored in #1675).
- Parse shortfalls: `idle_map` `sessionType` (the "Time mode" description is gone; no replacement signal), `waiting_for_offer` wait time (fixed in #1675), `dash_along_the_way` `spotSaveDeadline` (no "Spot saved until" line on these renders).

### Privacy sweep (new captures)

- No dasher banking text in any capture. No `Firstname L.` shape; two-word hits are chrome and product or chain names. `Deliver to`, `Delivery for`, name and address ids, subpremise and `Customer Notes` values masked everywhere checked. No PIN or gate code.
- Street-address hits are store addresses, except the lone street line in bug 5.
- Open: the order-number title on `pickup_pre_arrival` (raw); road names on pickup-leg navigation click UNKNOWNs (the dasher's own route); item lists (product names).

### New unruled surfaces

- "A new order is added to your shopping list" (on #1600).
- The offer item-list sheet ("Item list / Done"), "Can't verify items" and "Verification still in progress / Enter code" (on #1047).
- DoorDash's "This dash so far" mid-dash sheet (unruled on purpose); it carries "Offers accepted N out of M", independent evidence for bug 1.

### Open questions

- Does the pool need an accept signal for a same-store add-on (the "new order added" sheet, the "This dash so far" counter, or a second drop appearing under the job)? (#1600)
- Should the recognition-health ratio ignore offline browsing before it posts a notice? (#1560)
- Should a lone street line be masked in debug captures as well? (#1671)

### Verification TODOs

- On the next shifts: #1672 (a dismissed receipt stays dismissed), #1668 (no stray offline when Home is opened mid-shift), #1669 (the "This dash" pill reaches the running total), #1630 (shadow mode's daily report).
- A returns offer (#1673, new DoorDash offer type): keep the captures if one appears.
