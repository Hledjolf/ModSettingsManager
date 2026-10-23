# Changelog

## 1.0.2 (2026-10-22), Hytale 0.7.3

- Fix: `/modsettings set` read its value from the line Hytale had already tokenized, with escapes removed and the tokens joined again. Quotes typed to keep spaces were saved as part of the value (`"two  words"` became `"two words"`, quotes included), an escaped `\"` could not be told from a quote, and commas and square brackets came back with spaces around them (`a, b` as `a , b`). The value is now read from the line as typed, with Hytale's escapes applied once: everything after the key is the value, spaces, commas and brackets included; quotes around the whole value are removed; `\\`, `\"`, `\'`, `\,`, `\[` and `\]` give the character.
- Fix: the JSON reader took a `\u` escape with a sign (`\u+041`, `\u-001`) as a character instead of rejecting it; it now needs exactly four hex digits.

## 1.0.1 (2026-10-21), Hytale 0.7.3

Small fixes found while testing against a real mod.

- Fix: big numbers were clipped in number fields (a 13-digit Unix time showed as its last 11 digits). A number whose value, `min` or `max` needs more than 9 characters now gets a wider field; the -/+ steppers stay.
- **Display names:** a sidecar may now carry an optional top-level `"title"` (up to 60 characters, for example "Calendar & Seasons"). The mod list, the header, the status texts, and the chat and console messages use it instead of the manifest Name; search and `/modsettings <mod>` match it. Without a title the Name is shown with CamelCase split into words ("PlaytestNotes" becomes "Playtest Notes"). Version 1.0.0 rejects a sidecar that has `title`, so it falls back to inferred controls there.
- **Dropdown case:** dropdown values and entries keep their case (`HH:MM`, `H:MM A`) instead of being forced to capitals, so `mm` and `MM` can be told apart.
- Fix: the restart banner stayed up after Reset to default (or any change) put a restart-only setting back to the value the mod is running with.
- Fix: in singleplayer, the world's owner could look but not apply; Apply stayed greyed out. The owner can now edit, as with Hytale's own admin commands.
- Fix: opening the window disconnected the player ("CustomUI Set command couldn't set value ... #Label.TooltipText"). Tooltips are now sent as plain text, as the client expects.
- **A hook that throws is no longer reported as "restart needed".** The mod rejected the value, and a restart will not apply it. The window status and banner now say "Saved, but <mod> could not apply: <settings>. See the server log."; `/modsettings set` prints "Could not apply: <keys>"; `/modsettings list` shows `[could not apply]`; the mod list shows "(not applied)"; the log line and chat notice carry the same wording. The value is still saved before the hook runs, as before. A key marked `"restart": true` still waits for a restart. The exception message is logged at WARN.
- **Message grammar:** "1 change takes effect after a restart", "2 changes take effect after a restart"; a single change that waits reads "It takes effect after a restart".
- **Slow hooks:** a hook that takes longer than 1 second logs one WARN naming the plugin and the time, since it stalls the world thread.
- **README:** a note on typing values in chat or the console (double the backslashes, square brackets, runs of spaces).
- **Step grid:** `+`/`-` from a bound that is not a multiple of the step goes to the nearest multiple of the step (min 0.001, step 1: `+` gives 1, not 1.001). Documented in `docs/SIDECAR.md`.
- **No false "live":** a mod without a hook is never told "All in effect now". The message reads "<Mod> has no live hook. It takes effect after a restart, unless the mod re-reads its file while running."
- **Wrong-typed values can be fixed:** a file value that doesn't match the sidecar's `type` gets an editable control of the declared type, with the hint `the file holds "abc", not a whole number; fix this value`, instead of a read-only row. "not a int" is now "not a whole number" (also "true or false", "a number", "text").
- **Backup renamed:** the backup is `config.json.msm-bak`. The server's config codec writes its own `config.json.bak` on every save and overwrote ours.
- **Put-back warnings in commands:** `/modsettings list` tags the mod `[put back]`, and `show` prints the values the mod put back.
- **Docs:** commas and square brackets can't be typed in a `set` value (the window can); the README and sidecar guide no longer imply untouched lists and objects keep their layout (values are kept, layout may be normalized); a new "If your mod saves its config" note for mods that use the server's config codec.
- **Build:** a `checkNoProjectMarks` guard in `check`.
- Source reorganized into a game-agnostic core and a Hytale adapter; no change for server owners.

## 1.0.0 (2026-10-20), Hytale 0.7.2

First release.

- **The settings window** (`/modsettings`, alias `/msm`): mods on the left with a search box, sections and controls on the right. Checkboxes, number fields, text fields and dropdowns; lists and nested objects are shown read-only. Apply, Revert and Reset to default; unapplied changes are marked; a banner lists the changes that wait for a restart.
- **Works on any mod's existing `config.json`**, with controls inferred from the values. No code or dependency in the mod.
- **Sidecar files** (`Settings/<Group>_<Name>.settings.json`, format 1) add labels, descriptions, sections, types, ranges, steps, options, defaults, restart flags and hidden keys. Validated against the published schema (`schema/settings-sidecar.schema.json`); a bad sidecar is rejected with one clear log line, and the mod falls back to inferred controls.
- **`-`/`+` steppers** beside every number field. They step by the sidecar's `step`, or by 1 for whole numbers and by the default's last decimal for fractions. They stop at `min`/`max`, and the button that would leave the range is disabled. One step per click (raw pages have no press-and-hold event). A step counts as an edit and is saved with Apply.
- **Live apply** (pulled forward from 1.1): after writing a config, Mod Settings Manager calls the mod's `public boolean onSettingsChanged(Map<String, Object>)`, if it has one, on the default world thread. `false` or a throw means "restart needed".
- **Safe writes:** values are validated first; the file is written to a temp file and renamed over the original; unknown keys, key order and indentation are kept; `config.json.bak` is kept; each change is appended to `changes.log`.
- **Put-back check:** after a restart, each file Mod Settings Manager wrote is read again. If a mod put a value back (for example by saving its config on shutdown), it logs a warning and shows it in the window.
- **Permissions:** anyone can look; changes need operator status or `modsettings.edit`.
- **Text commands:** `/modsettings list`, `show <mod>`, `set <mod> <key> <value>` and `reload`, which also work from the server console.
- **Own settings:** `AnnounceChanges` (tell online editors in chat about each change, applied live through the hook) and `HiddenPlugins`.

Moved to a later version:

- Sliders for numbers with a known range: no vanilla page sets a slider's range from the server, and that can't be verified without a client. Numbers use number fields with `-`/`+` steppers, with the range shown and enforced.
- Per-player settings, editable lists and objects, and profiles stay in 1.1 as planned.
