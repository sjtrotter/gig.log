status: active
issues: #1668

- **Going offline is seen on the offline home drawn without its Dash button (#1668):** on DoorDash 8.107.10 or later, end a dash (or open the app while offline) and watch DoorDash's home screen. It often draws the map and the "Avg. offer wait" stat before, or without, its "Dash" button. gig.log should go offline within a second or two of that screen: the bubble leaves the live shift. The watch: during a live dash, open DoorDash's home and side menu once; gig.log must stay online and the shift must not end. Known limit: the first frames right after End dash carry only chrome the online home shares, so they stay unrecognized and the shift end can still land about 10 s late; that is not a failure of this item.
  - Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, DoorDash 8.108.11): not exercised (both ends came through the summary). About 36 UNKNOWN frames from the offline home and side-menu family on 8.108.11, some with "Avg. offer wait" beside "Dash"; which of them were offline homes this item's rule should claim needs a look. Counter unchanged.
