status: must-watch
issues: #1672

- **A dismissed receipt stays dismissed (#1672):** after a drop-off, the delivery summary opens its pay breakdown
  by itself once. Dismiss it: it stays dismissed and the next screen comes up, with no second opening.
  - An offer that pops up over the opened receipt and goes away leaves the receipt as it was; the app does not
    open it again.
  - A first expand that fails (the breakdown stays collapsed) still retries on its own within a second or two.
  - In the log: one `Performing expand_earnings` line (INFO, tag `Effects`) per receipt, or two when the first
    attempt failed closed. A `#1672 receipt already expanded` line (DEBUG, tag `StateMachine`) marks a pending
    expand the app dropped. — Confirmed: 0/2
    - desk 11-21 (pull of the 11-20 shifts, build `c4ada68c`, DoorDash 8.108.11): 4 `Performing expand_earnings` lines for 4 receipts and one `#1672 receipt already expanded … dropped` at 14:09:43, seen working in the 11-20 pull, awaiting the developer's confirmation. Counter unchanged.
