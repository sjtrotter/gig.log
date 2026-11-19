status: active
issues: #1566

- **The gas price comes from the fuel feed for the area picked in Settings > Costs (#1566).** With Auto gas price on,
  open Settings > Costs and pick an area under Fuel price area. Working when: the gas price changes at once, with no
  refresh tap; with a diesel vehicle active and a state picked (Texas, say), Settings says "Using the Gulf Coast price"
  (the state's region); the bubble's gas quick edit and Settings read "EIA, week of <Monday>", a Monday date, never the
  Sunday before it. With auto off, the bubble's "Use auto price" turns auto on only once a price arrives; in airplane
  mode it shows the error and the price stays manual. Broken = a price that needs a refresh to follow the area, a
  Sunday date, auto turning on with no new price, or "Older price" on a price from this week. The "Older price" label
  itself is a desk check unless the feed lapses.
  - Confirmed: 0/2
