# Changelog

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
