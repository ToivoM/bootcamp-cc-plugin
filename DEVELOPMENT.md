# Development

This plugin is published on the RuneLite Plugin Hub. Every feature must pass the Plugin Hub review and
follow Jagex's third-party client guidelines, so read the checklist below **before** designing a feature,
not after building it.

Sources (check them again when in doubt, they change):

- [Jagex third-party client guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1)
- [RuneLite: Rejected or rolled-back features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features)
- [Plugin Hub README](https://github.com/runelite/plugin-hub/blob/master/README.md)

## Checklist for every new feature

### Code

- [ ] Java only, compatible with Java 11 (`options.release.set(11)` in `build.gradle`).
- [ ] No reflection, JNI/JNA, `Unsafe`, external processes (`Process`/`ProcessBuilder`), runtime code
      generation, class loading or downloading code, or Java (de)serialization.
- [ ] No third-party dependencies. Only what `net.runelite:client` already provides (OkHttp, Gson, Guice,
      Lombok). Never add RuneLite's transitive dependencies (gson, okhttp, guice, ...) to `build.gradle`.
- [ ] HTTP only through `ApiClient` (injected `OkHttpClient` and `Gson`, always `enqueue`). Never block
      the client thread with network or disk I/O. Use `clientThread.invoke(...)` to get back to the client
      thread from a callback.
- [ ] No `Thread.sleep()`; nothing blocks in `startUp()`/`shutDown()`. Everything registered in
      `startUp()` (overlays, panels, scheduled tasks, listeners) is removed or cancelled in `shutDown()`.
- [ ] File I/O only through RuneLite's `Filepath` utility (needs `internalName` in `@PluginDescriptor`).
- [ ] Use `net.runelite.api.gameval` constants (`ItemID`, `InterfaceID`, ...) instead of magic numbers.
- [ ] `log.debug` for diagnostics; `log.info` only for rare one-off events.
- [ ] Config: never rename the group (`bootcampcc`) or a key without a migration (a rename silently
      resets users' settings).

### Data and network

- [ ] Only the player's **own** data. Never collect or send data about other players (names, locations,
      gear, ...), and never expose player information over HTTP.
- [ ] Every feature that talks to a server is **opt-in**: a `@ConfigItem` toggle that is off by default and
      has this warning:
      `"This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"`
- [ ] The README says what the feature sends and when.

### Game interaction

- [ ] No typing into the chatbox (no autotyping, pasting or shorthand expansion) and no changing chat
      messages after the player sends them.
- [ ] No injected mouse or keyboard input.
- [ ] No boss or combat helpers: attack prediction, prayer switching hints, attack counters, "where to
      stand" indicators, projectile/landing markers, future hazard warnings, NPC target identification, or
      simulators. This covers raids, slayer bosses, demi-bosses and wave minigames.
- [ ] No PvP helpers (opponent scouting, freeze timers, attackable-player highlights, ...).
- [ ] No new menu entries that send an action to the server; no conditional menu entry removal.
- [ ] No unhiding hidden interface components and no moving or resizing click zones.
- [ ] Overlay and panel clicks only consume clicks on the plugin's own components; everything else goes
      through to the game.

## Running locally

Requirements: JDK 11 or newer (17 is used in CI).

```sh
./gradlew run
```

This starts a RuneLite development client (`--developer-mode --debug`) with the plugin loaded. To log in
with a Jagex account, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).

Never automate game input to test the plugin (scripts, screen-control tools). Test in-game by hand.

## Testing

```sh
./gradlew build   # compile + unit tests (what CI runs)
./gradlew test    # unit tests only
```

Unit tests live in `src/test/java/com/bootcampcc/`. Keep logic that can be tested (parsing, URL handling,
state) in plain classes without the game client, and test it there. `BootcampCCPluginTest` is not a test:
it is the launcher used by `./gradlew run`.

GitHub Actions runs `./gradlew build` on every push and pull request
(`.github/workflows/build.yml`). Do not release a commit that is not green.

## Releasing on the Plugin Hub

The Plugin Hub builds the plugin from a specific commit of this repository.

### First release

1. Make sure `main` is green in CI and `runelite-plugin.properties` is correct.
2. Fork [runelite/plugin-hub](https://github.com/runelite/plugin-hub).
3. Add the file `plugins/bootcamp-cc`:
   ```
   repository=https://github.com/ToivoM/bootcamp-cc-plugin.git
   commit=<full 40-character commit hash>
   ```
4. Open a pull request. The Plugin Hub CI builds the plugin; a RuneLite developer reviews the code.
   Answer review comments with new commits here and update the hash in the PR.

### Updates

1. Merge the change to `main` and wait for CI to pass.
2. Test the change in-game with `./gradlew run`.
3. In your plugin-hub fork, update `commit=` in `plugins/bootcamp-cc` to the new full hash and open a PR.
   Write a short summary of the changes in the PR description.
4. After the PR is merged, players get the update automatically the next time RuneLite starts.

Notes:

- Every update is reviewed again, so keep updates focused and the diff readable (do not mix reformatting
  with feature changes).
- Changes that add network features, new data or anything near the rules above get more scrutiny:
  explain them in the PR.
- `runelite-plugin.properties` (`displayName`, `description`, `tags`) is what players see in the
  Plugin Hub list.
