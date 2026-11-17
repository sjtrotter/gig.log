status: active
issues: #1527

- **A shift with no earnings ends when DoorDash shows the offline home (#1527):** on DoorDash 8.106.9 or later, start a dash and end it before taking an offer, so DoorDash shows no dash summary. Back on DoorDash's home screen with the "Dash" button, gig.log should go offline within a second or two: the bubble leaves the live shift and the shift's end time matches when you ended it. Also open DoorDash's side menu during a live dash once: the home behind it reads "You're dashing now" and "Return to Dash", and gig.log must stay online there.
  - Confirmed: 0/2
    - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised as written (all four shifts ended on the summary). `idle_map` matched on 8.106.9 on both days. Counter unchanged.
    - desk 11-16 (pull of 11-15 and 11-16, build `7a193657`, DoorDash 8.107.10): S3 is the scenario (no offers, no summary). The shift ended 11 s after the End dash tap, not a second or two: the offline home drew without its Dash button under a workflow host for 9 s. #1675 re-anchors the button-less home; the post-End-dash frames still stay UNKNOWN (#1668 open). CONTRADICTS the timing. Counter unchanged.
