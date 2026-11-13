# Bootcamp CC

A [RuneLite](https://runelite.net) plugin for the Bootcamp CC clan.

- **Broadcasts**: short messages from the clan admins appear in your chatbox, and new ones also in a box
  above the chatbox.
- **Events**: the clan's Discord events in a side panel (Live now, Upcoming, Past), updated within seconds
  when an event is created or changed in Discord.

Both need a linked account. Until you link, the plugin sends nothing anywhere.

## Getting started

1. Install **Bootcamp CC** from the Plugin Hub.
2. In the Bootcamp CC Discord, run `/link`. You get a one-time code (valid for 15 minutes).
3. Log in to the game, open the Bootcamp CC plugin settings and paste the code into **Link code**.
4. The chatbox says `[Bootcamp CC] Account linked.` and the code field is cleared.

Linking is per RuneScape account: link each of your accounts separately. Linking the same account again
(with a new code) replaces the old link.

## Settings

| Section    | Setting            | Default | Description |
|------------|--------------------|---------|-------------|
| Account    | Link code          | (empty) | Paste the code from `/link` here. |
| Broadcasts | Receive broadcasts | on      | Show clan broadcasts in your chatbox (once linked). |
| Broadcasts | Overlay            | on      | Also show new broadcasts in a box above the chatbox. |
| Broadcasts | Overlay duration   | 30 s    | How long a broadcast stays in the box (5-300 s). |
| Broadcasts | Notify             | off     | Send a RuneLite notification for new broadcasts. |
| Events     | Side panel         | on      | Show the events panel in the sidebar. |
| Advanced   | API URL            | (empty) | Address of the Bootcamp CC server. Only `https://` (or `http://` for localhost). |

## Broadcasts

- A broadcast less than 5 minutes old is shown in the chatbox and in the box above the chatbox, one at a
  time, with a counter (`1/3`) when several are waiting, a bar for the time left, and an **X** to close it.
- Older ones (for example sent while you were offline) are shown in the chatbox only, with their age:
  `[Bootcamp CC] (3 h ago) message`. At most the 5 newest are listed, with `+ N older broadcasts` before
  them. Broadcasts older than 24 hours are never shown.
- If an admin retracts a broadcast, it is removed from the box (or the box closes if it is showing). A line
  already in the chatbox stays.
- The box has no right-click menu, and only a left click on its X is used by the plugin; every other click
  goes to the game.

## Events

The side panel lists the clan's Discord events: **Live now** (green, with a LIVE badge), **Upcoming** (blue;
SOON when it starts within an hour) and **Past** (grey, last 30 days). Times are in your computer's time zone,
with relative times ("starts in 2 h 15 min") that update every 30 seconds. Click an event to show its full
description.

## Data sent to a third-party server

The Bootcamp CC server is run by the clan, not by RuneLite or Jagex. When the plugin talks to it, the
server sees your **IP address**.

- **Before you link: nothing.** The plugin makes no network requests at all until you enter a link code.
- **When you link** (once): the link code and your RuneScape name. The server answers with a token, which
  the plugin stores in your RuneLite settings for this RuneScape account. The server stores only a sha256
  hash of the token, never the token itself, together with your RuneScape name and the Discord account that
  ran `/link`.
- **While you are logged in and linked**, and Receive broadcasts or the events panel is on: the plugin keeps
  one request open to receive broadcasts and event changes. With the events panel on it also loads the event
  list at login, when it changes, when you open the panel and every 5 minutes (and checks the list's version
  every 60 seconds if the open request is failing). These requests send the token and at most two numbers
  (the newest broadcast and the event list version you have), nothing else.
- The plugin never sends anything about other players, your gameplay, your chat or your location.

To stop all requests, turn off Receive broadcasts and the events panel, or disable the plugin. Changing the
API URL forgets the link.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md).

## License

[BSD 2-Clause](LICENSE)
