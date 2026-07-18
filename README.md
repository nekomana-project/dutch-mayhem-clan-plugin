# Dutch Mayhem Clan

A RuneLite plugin for members of the **Dutch Mayhem Clan (DMC)**. It bridges your OSRS client with the clan's Discord bot: clan chat mirroring, wilderness/PvP loot-split tracking and clan bingo events.

The plugin does nothing until you configure it — with an empty **Server URL** no data ever leaves your client.

## Features

- Forwards clan chat messages to the DMC Discord (clan chat mirror channel).
- Tracks PvP loot (direct kills and loot key chests) in the wilderness and on PvP/Deadman worlds, for the clan's loot-split sessions.
- Reports PvP kills and deaths with map position, so the clan website can plot them.
- Reports bingo-relevant events during clan bingo: drops, level-ups, quest completions, collection log entries, combat achievements, clue scrolls and achievement diaries.

## Setup

1. Install the plugin from the RuneLite Plugin Hub.
2. Open the plugin settings panel (search for **Dutch Mayhem Clan**).
3. Fill in the two fields — values come from the DMC Discord:
   - **Server URL** — posted in `#bot-info`
   - **Personal Token** — run `/mytoken` in the DMC Discord

## What data is sent, and where

Everything goes to the clan's own server (the Server URL you configure), authenticated with your personal token. Sent when the relevant event happens:

- Clan chat lines (sender + message) from the DMC clan chat.
- Your RSN with loot drops, kills/deaths (including the killed/killing player's name and your map position at that moment) — only in the wilderness or on PvP/Deadman worlds.
- Your RSN with bingo progress events (drops, level-ups, quests, collection log, combat achievements, clues, diaries).

Data goes to the DMC server and nowhere else. While the Server URL is empty, nothing is sent at all.
