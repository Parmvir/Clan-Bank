# Clan Bank — RuneLite Plugin

A generic companion for **any** clan running the [osrs-clan-bank-bot](../osrs-clan-bank-bot)
Discord bot — not tied to one specific clan. It fetches your clan's name
straight from Discord and shows it in the overlay/panel title, so the same
plugin build works for whichever server's bot you point it at.

Shows your current clan bank loan status in-game, and lets you request or
settle a loan from the sidebar panel without switching to Discord. Every
action still goes through the exact same officer-approval flow as the
bot's Discord commands — nothing is auto-approved.

## What it does

- Overlay (top-left, in-game): a compact "owed / pending approval" summary
- Sidebar panel (click the toolbar icon): full breakdown of what you owe,
  with item icons, plus two forms:
  - **Request Item** — search-as-you-type (with item icons and a
    🟢/🟡/🔴 stock indicator, like the Discord panel), pick a match to see
    its price/stock/valid final products, set a quantity, and submit — a
    live summary shows the total value and exact repayment terms before
    you send it.
  - **Return / Settle Loan** — pick from what you currently have on loan
    and submit a physical return or gp buyback payment.

If your character has no bank activity yet, or the bot isn't reachable, it
says so instead of showing stale data.

## Setup

See [SETUP.md](SETUP.md) for configuring the companion bot, running the
plugin during development, and project layout.
