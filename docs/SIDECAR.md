# Sidecars and live apply: a guide for mod authors

**Your mod needs no dependency on Mod Settings Manager.** If it keeps a `config.json` in its data folder, it already shows up in the settings window, with a control chosen for each value. The two extras below are optional:

- **A sidecar** is a small JSON file that gives your settings labels, descriptions, sections, ranges, dropdowns and defaults. It is plain data. When Mod Settings Manager isn't installed, nothing reads it, so it costs your mod nothing.
- **The live-apply hook** is one public method on your plugin class. Mod Settings Manager finds it by reflection, so there is no compile dependency. Without Mod Settings Manager, nothing calls it.

The format and the schema are MIT licensed. Any mod may ship a sidecar, open source or not.

## A sidecar in one minute

Put this in your jar at `Settings/<Group>_<Name>.settings.json`, where `Group` and `Name` come from your `manifest.json`:

```json
{
  "format": 1,
  "plugin": "MyGroup:MyMod",
  "config": "config.json",
  "sections": [ { "id": "turns", "label": "Turns" } ],
  "keys": {
    "DaySeconds": {
      "label": "Day length (seconds)",
      "description": "Tooltip text.",
      "section": "turns",
      "type": "int",
      "min": 60, "max": 7200, "step": 60,
      "default": 1200,
      "restart": false
    },
    "DispelTargets": { "type": "enum", "options": ["enemy", "any"], "default": "enemy" },
    "SomeInternalKey": { "hidden": true }
  }
}
```

With a resources folder that's `src/main/resources/Settings/MyGroup_MyMod.settings.json`.

Server owners can also put a sidecar in your mod's data folder, as `mods/<Group>_<Name>/Settings/<Group>_<Name>.settings.json` or directly as `mods/<Group>_<Name>/<Group>_<Name>.settings.json`. One there wins over the one in the jar. That's handy for a mod whose author hasn't written one yet.

## The fields

### Top level

| Field | Required | Meaning |
| --- | --- | --- |
| `format` | yes | Always `1` for this version of the format. A newer format is rejected with a message to update Mod Settings Manager. |
| `plugin` | yes | Your plugin as `Group:Name`. It must match the plugin the file was found for (case doesn't matter). |
| `config` | no | The config file's path relative to your data folder, for example `"settings/server.json"`. Default `config.json`. It must stay inside the data folder and end in `.json`. |
| `sections` | no | A list of `{ "id", "label", "description" }`, shown in this order. `id` is letters, digits, `_`, `.` and `-`. |
| `keys` | yes | One entry per key you want to describe (it may be empty: `{}`). |
| `$schema` | no | Allowed, for editors. |

### Per key

Every field is optional.

| Field | Meaning |
| --- | --- |
| `label` | Shown instead of the key name. Without it, the key is made readable: `DaySeconds` shows as "Day Seconds". |
| `description` | Tooltip text. |
| `section` | The `id` of one of your sections. Keys without a section, and keys the sidecar doesn't list, go under "Other". |
| `type` | `bool`, `int`, `float`, `enum` or `string`. Without it, the type is inferred from the value in the file. |
| `min`, `max` | The allowed range for `int` and `float`. Shown next to the field; Apply refuses values outside it. |
| `step` | How far the `-`/`+` buttons move an `int` or `float`. Without it: 1 for `int`, and one unit of the default's last decimal for `float` (`0.5` gives 0.1). Stepping from a `min` or `max` that isn't a multiple of the step goes to the nearest multiple of the step (`min` 0.001, `step` 1: `+` gives 1, not 1.001); after that, steps are plain. |
| `options` | The allowed values, as strings. Shown as a dropdown. `options` alone implies `type: "enum"`. |
| `default` | The value **Reset to default** fills in. It must fit `type`, `min`, `max` and `options`. |
| `restart` | `true`: the change takes effect only after a restart. `false`: your mod picks it up while running (by re-reading the file, or through the hook). Leave it out if unsure. |
| `hidden` | `true`: never shown. Hiding an object key hides everything inside it. |

### A value of the wrong type

If the file holds a value that doesn't match the sidecar's `type` (text where `type` is `int`), the key still gets the control of the declared type, with the hint `the file holds "abc", not a whole number; fix this value`. Applying a valid value (or Reset to default) repairs the file.

### Keys you don't list

They still show up, with a control inferred from their value: a checkbox for `true`/`false`, a number field for numbers, a text field for strings, and a read-only display for lists, objects and `null`. Use `"hidden": true` for anything players shouldn't touch.

### Nested values: dotted keys

`"Seeder.CellSize"` addresses `CellSize` inside the object `Seeder`:

```json
{ "Seeder": { "CellSize": 32, "Jitter": 0.25 } }
```

Normally a nested object is one read-only row. Once the sidecar names any key inside it, each value inside that object gets its own row. A key that literally contains a dot (`"a.b": 1`) is found too.

### Keys that aren't in the file yet

A key listed in the sidecar with a `default`, but missing from the file, is shown with its default and the note "not in the file yet". It's written to the file only if someone changes it. Keys without a default that are missing from the file are not shown.

## Validation

Mod Settings Manager validates each sidecar at boot, against [`schema/settings-sidecar.schema.json`](../schema/settings-sidecar.schema.json) (JSON Schema draft 2020-12) and a few rules a schema can't express. Point your editor at the schema with `"$schema"` to get completion and errors while you type.

**Errors reject the sidecar.** The server log gets one line naming the file and every problem, for example:

```
[ModSettingsManager] Rejected sidecar mymod.jar!/Settings/Me_MyMod.settings.json for Me:MyMod: $.keys.Count.type: "integer" is not one of ["bool","int","float","enum","string"]. Its settings are shown with inferred controls instead.
```

Your settings still show, as if there were no sidecar. Errors include: bad JSON, a missing required field, an unknown field (typos such as `lable` are caught), a wrong type, `plugin` not matching, `min` above `max`, an `enum` without `options`, a `default` that doesn't fit, a fractional `min`, `max` or `step` for an `int`, a `config` path outside the data folder, and a section id used twice.

**Warnings are logged, and the sidecar is still used.** They cover a `section` that isn't in `sections`, `options` without type `enum`, and `min`, `max` or `step` on a non-number.

After editing a sidecar in a data folder, run `/modsettings reload` instead of restarting.

## The live-apply hook

Add this public method to your main plugin class, the one that extends `JavaPlugin` and is named in `manifest.json` as `Main`:

```java
public boolean onSettingsChanged(java.util.Map<String, Object> changed) {
    // Re-read what you need. The file is already written when this runs.
    reloadMyConfig();
    return true; // every change took effect live
}
```

- **When:** right after Mod Settings Manager has written your config file.
- **The argument:** each changed key (dotted for nested ones) mapped to its new value, as a JSON-decoded `Boolean`, `Long`, `Double` or `String`. The map is read-only.
- **The return value:** return `true` if every change took effect live. Return `false` to mean "restart needed"; the window then shows the restart banner for those keys.
- **Throwing:** throw only for a value your mod rejects. The value is already saved, because the file is written before the hook runs, and a restart would not help. Mod Settings Manager reports this as "Saved, but <your mod> could not apply: <settings>. See the server log." (not as a restart), logs your exception message at WARN, and shows the setting as not applied until a later change goes through. A key marked `"restart": true` in the sidecar still waits for a restart.
- **Be quick:** the hook runs on the world thread. One that takes over 1 second gets a WARN in the log naming your mod and the time.
- **Threading:** the call is made on the server's default world thread.
- **Without the hook:** changes to keys without `"restart": false` in your sidecar are treated as needing a restart. Keys marked `"restart": false` are your promise that the mod re-reads the file while running; Mod Settings Manager can't check it, so it never says "All in effect now" for a mod without a hook. It says "<Mod> has no live hook. It takes effect after a restart, unless the mod re-reads its file while running."

The method takes only JDK types, so your mod compiles and runs the same with or without Mod Settings Manager.

### Which changes need a restart

| | `restart: true` | `restart: false` | not given |
| --- | --- | --- | --- |
| No hook | restart | live | restart |
| Hook returns `true` | restart | live | live |
| Hook returns `false` | restart | restart | restart |
| Hook throws | restart | could not apply | could not apply |

### If your mod saves its config on shutdown

Then an edit made while the server runs is overwritten when it stops. Mod Settings Manager detects this after the next restart and warns the server owner, but the edit is lost. To avoid it, either don't write the config on shutdown, or add the hook and update your in-memory config from the file in it.

### If your mod saves its config

Mods that keep their settings with the server's own config codec (`withConfig` / `Config.save()`) rewrite the whole file whenever they save: keys the codec doesn't know are dropped, keys are put back in the codec's order, and the old file is copied to `config.json.bak` (Mod Settings Manager's own backup is `config.json.msm-bak`, so the two don't collide). Loading never changes the file. If such a mod saves on shutdown, edits made while the server runs are put back, and Mod Settings Manager warns after the restart. A value of the wrong type in a `withConfig` file stops the server from booting, so edit these files only through the window or with care.

## How the file is written

- Only the changed keys are written, into the file as it is at that moment, so hand edits made while the window was open are kept.
- Key order, keys Mod Settings Manager doesn't know about, the file's indentation and every value are kept. The layout of lists and objects (including ones no one touched) may be normalized: short lists of plain values go on one line, longer ones one item per line, and a hand-written one-line object is expanded. Numbers keep their style: a value written as `5` stays a whole number, and one written as `5.0` keeps its fraction.
- The old file is saved as `config.json.msm-bak`, not `.bak`, which the server's own config codec uses. The new one is written to a temporary file and renamed over the original.
- Each change gets a line in Mod Settings Manager's `changes.log`.

## Checklist

1. The file is at `Settings/<Group>_<Name>.settings.json` in your jar, with `Group` and `Name` exactly as in your manifest.
2. `"format": 1` and `"plugin": "<Group>:<Name>"` are set.
3. Every key name matches your config file exactly (case matters).
4. Each `default` matches what your mod writes on first run.
5. `restart` is `false` only for keys your mod really picks up while running.
6. The server log has no `Rejected sidecar` line for your mod, and `/modsettings list` shows `[sidecar]` next to it.
