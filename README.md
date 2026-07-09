# Clan Bank — RuneLite Plugin

A generic companion for **any** clan running the [osrs-clan-bank-bot](../osrs-clan-bank-bot)
Discord bot — not tied to one specific clan. It fetches your clan's name
straight from Discord and shows it in the overlay/panel title, so the same
plugin build works for whichever server's bot you point it at.

Shows your current clan bank loan status in-game, and lets you request or
settle a loan from the sidebar panel without switching to Discord. It talks
to a small local API exposed by the Discord bot; it does not read or
interact with the game beyond your own character's name, and it never sends
game input — no automation, no macroing. Submitting a request from the
panel just makes an HTTP call to the bot, the same as typing a Discord
command; every action still goes through the exact same officer-approval
DM flow as the slash commands, nothing is auto-approved.

## What it does

- Overlay (top-left, in-game): a compact "owed / pending approval" summary
- Sidebar panel (click the toolbar icon): full breakdown of what you owe,
  with item icons, plus two forms:
  - **Request Item** — search-as-you-type (with item icons and a
    🟢/🟡/🔴 stock indicator, like the Discord panel), pick a match to see
    its price/stock/valid final products, set a quantity, and submit — a
    live summary shows the total value and exact repayment terms before
    you send it. Sends an approval DM to officers exactly like `/borrow`
    or the Discord panel would.
  - **Return / Settle Loan** — pick from what you currently have on loan
    and submit a physical return or gp buyback payment, same as
    `/return-item` / `/pay-loan`

If your character has no bank activity yet, or the bot isn't reachable, it
says so instead of showing stale data.

## Setup

### 1. Configure the bot side first

In `osrs-clan-bank-bot/.env`, set:

```
PLUGIN_API_PORT=3939
PLUGIN_API_KEY=<any long random string>
```

Restart the bot. You should see `Plugin API listening on http://127.0.0.1:3939` in its console output.

By default this only listens on `127.0.0.1` (localhost) — the plugin has to
run on the **same computer** as the bot for this to work out of the box. If
you need the plugin on a different machine, see the note in `.env.example`
about `PLUGIN_API_HOST` first — that widens network exposure of member loan
data, so treat the API key as an actual secret if you do that.

### 2. Run it via Gradle (no plugin hub, no sideloading tool needed)

This project includes `src/test/java/.../ClanBankPluginTest.java` — a tiny
launcher that starts a full RuneLite client with this plugin already loaded
and enabled. This is RuneLite's own standard way of running a plugin under
development (it's the same pattern their official `example-plugin` template
uses), and it uses your existing RuneLite login/profile from `~/.runelite`,
same as your normal client.

`build.gradle` defines a `runPlugin` task that launches it with the exact
JVM flags RuneLite needs (see below) — use this instead of IntelliJ's
"Run main()" gutter button, which delegates to an ad-hoc Gradle task that
doesn't reliably apply VM options and will fail with an assertions error.

1. Install **IntelliJ IDEA Community Edition** (free) and a JDK — 11 is what
   the build targets, but any JDK 11+ (17, 21, etc.) works fine for
   compiling it; use whatever your JDK downloader offers.
2. Open this folder (`creeps-inc-clan-bank-plugin/`) in IntelliJ as a Gradle
   project — it'll prompt to import the build and download RuneLite's
   dependencies (needs internet the first time; can take a few minutes).
3. Run it either:
   - **From a terminal**: `./gradlew runPlugin`
   - **From IntelliJ**: open the Gradle tool window (right-hand sidebar) →
     `creeps-inc-clan-bank` → Tasks → `application` → double-click
     `runPlugin`.
4. A RuneLite window opens. Log in as normal — the plugin is already active, no need to find it in the plugin list (though it'll show up there too, enabled).

Every time you want the plugin, launch RuneLite this way instead of your
usual shortcut. A pre-built jar (`creeps-inc-clan-bank-1.0.0.jar`) is also in
this folder if you'd rather try a sideloading tool like External Plugin
Manager on your normal client — either path works, this one just doesn't
depend on a third-party tool.

**JVM flags baked into `runPlugin` (for reference, if you ever run it another way):**
RuneLite requires assertions enabled (`-ea`), and on JDK 17+ its macOS
fullscreen support needs a few `java.desktop` internals reopened:
```
-ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.awt.image=ALL-UNNAMED --add-opens=java.desktop/sun.java2d=ALL-UNNAMED
```

### 3. Configure in-game

Open RuneLite's plugin settings for "Clan Bank" and fill in:

- **Bot API URL** — `http://localhost:3939` (or wherever the bot's `PLUGIN_API_PORT` is reachable)
- **API Key** — must exactly match `PLUGIN_API_KEY` in the bot's `.env`
- **Refresh interval** — how often to poll (default 30s)

The overlay should appear top-left once you're logged in.

## Project layout

```
build.gradle                 Gradle build config
runelite-plugin.properties   Plugin metadata (hub-style, harmless if unused)
src/main/java/com/creepsinc/clanbank/
  ClanBankPlugin.java        Entry point — polls the bot API on a timer
  ClanBankConfig.java        Settings panel definition
  ClanBankApiClient.java     HTTP client for the bot's /status endpoint
  ClanBankStatus.java        JSON response data model
  ClanBankOverlay.java       Renders the in-game panel
src/test/java/com/creepsinc/clanbank/
  ClanBankPluginTest.java    Run this to launch RuneLite with the plugin loaded
```

## Why this doesn't need Jagex/RuneLite approval concerns

This plugin only ever makes HTTP requests to a server you control — GETs to
render status as text on screen, and POSTs (submitting a borrow/return
request) that are functionally identical to a member typing a Discord slash
command, still requiring officer approval. It never calls any RuneLite
input API, never clicks or queues in-game actions, and never reads anything
beyond the logged-in player's own display name. That's the same category as
plugins like loot trackers or GE price checkers that are already on the
official Plugin Hub — the network calls aren't game automation, they're a
UI shortcut to a request an officer still has to approve.
