status: active
issues: #1601

- **A one-offer stacked job settles its own receipt (#1601):** a one-offer stack of two stops, with no other offer
  shown from the accept to the receipt, and the receipt read on the last drop. After the shift the receipt line does
  not ask to be matched; on the shift page the job's deliveries read "Receipt split" with an even (or by-store) split
  that adds up to the receipt, and Match on any other open receipt of the shift lists them locked as "Settled from
  receipt $X". Undo match on the job sheet puts the estimates back. Desk: one `#1601 job … settled r1` INFO line per such job, and the DEBUG
  `#1601 job … not settled: <gap>` lines say which clause held back the others. Broken = a stack with an offer shown
  mid-job settled anyway, shares that do not add up to the receipt, a settled row later re-priced, or a settled row
  reading "Matched by you".
  — Confirmed: 0/2
