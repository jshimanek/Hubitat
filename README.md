# Multi-Vendor Color Bulb Controller (Hubitat App)

A Hubitat app that controls color bulbs from **different manufacturers together**:

- **On / Off** all selected bulbs at once
- **One common color** (named preset, custom HSL, or white color-temperature) applied to every bulb
- **Light patterns / effects** — discovers the manufacturer-defined effects each
  bulb advertises and lets you apply a common one to all, pick per-bulb, or cycle
  next/previous.
- **Scenes** — save each bulb's power/color/effect and recall it later.
- **Color show** — random or rotating colors on a configurable interval.
- **Automations** — trigger on/color/scene from motion sensors and a daily time.

## Why this works across brands

Hubitat abstracts every vendor's bulb behind standard **capabilities**. Instead of
calling each manufacturer's private cloud/HTTP API, the app talks to the device
driver, which already normalizes brand differences. The app uses:

| Feature            | Capability              | Commands / attributes used                          |
|--------------------|-------------------------|-----------------------------------------------------|
| Device selection   | `colorControl`          | (used to filter the device picker)                  |
| On / Off           | `switch`                | `on()`, `off()`, `switch` attribute                 |
| Common color       | `colorControl`          | `setColor([hue,saturation,level])`                  |
| White temperature  | `colorTemperature`      | `setColorTemperature(k)`                            |
| Light patterns     | `lightEffects`          | `lightEffects` attr (JSON), `setEffect(id)`, `setNextEffect()`, `setPreviousEffect()` |

### About "custom light patterns"

In Hubitat, a bulb's built-in patterns are exposed through the **`lightEffects`**
capability. Each driver publishes a JSON map of `{id: "Effect Name"}` in the
`lightEffects` attribute — this *is* the API surface for patterns. The app reads
that map live from each device, so you only ever see the effects a bulb actually
supports.

- Bulbs **with** effect support (e.g. many LIFX, Sengled, Inovelli, some Zigbee RGBW):
  their patterns appear automatically.
- Bulbs **without** it: they simply show "no light-pattern support reported." Basic
  on/off/color still works for them.

#### Preset effects fallback (drivers that don't advertise a list)

Some drivers declare the `LightEffects` capability and accept `setEffect(number)`
but never publish the `lightEffects` attribute — so the effect list can't be
auto-discovered (ivarho's "Tuya Generic RGBW Bulb" driver is one: the effect names
live only in code comments). For any selected bulb that supports `setEffect` but
advertises nothing, the effects page shows a **named preset dropdown** (Good night,
Reading, Sunrise, Halloween, …) built into the app; picking a name sends the matching
effect number. There's also an **"apply preset to all compatible bulbs"** shortcut.

Caveat: because these drivers report nothing back, the app can't confirm which effect
is active, so the status table and scene capture won't record an effect name for them
(color/power are still captured normally).

> Note: effect *names* differ between brands, so a pattern is only offered in the
> "Common patterns" list when **every** selected bulb advertises that exact name.
> The per-bulb section always shows each device's full list.

## Install

Hubitat apps are installed as source, not from files on disk. Two options:

### Option A — Paste the code
1. Open your Hubitat hub UI → **Apps Code** → **New App**.
2. Copy the contents of [`MultiVendorColorController.groovy`](MultiVendorColorController.groovy) and paste it in.
3. Click **Save**.
4. Go to **Apps** → **Add User App** → select **Multi-Vendor Color Bulb Controller**.

### Option B — Import by URL
Use **Apps Code → New App → Import** and paste the raw URL:

```
https://raw.githubusercontent.com/jshimanek/Hubitat/master/MultiVendorColorController.groovy
```

### Option C — Hubitat Package Manager (HPM)

This repo ships a [`packageManifest.json`](packageManifest.json) so HPM can install and
keep the app updated.

**Install:**
1. In Hubitat, open **Hubitat Package Manager**.
2. Choose **Install → From a URL**.
3. Paste the manifest raw URL:
   ```
   https://raw.githubusercontent.com/jshimanek/Hubitat/master/packageManifest.json
   ```
4. Follow the prompts. HPM installs the app code for you; then add the app under
   **Apps → Add User App**.

The package also includes an **optional** patched driver, *tuya Generic RGBW Bulb*
([drivers/tuyaGenericBulbRGBW.groovy](drivers/tuyaGenericBulbRGBW.groovy)) — HPM will
offer it during install. Select it only if you have Tuya Wi‑Fi RGBW bulbs; it fixes
local-key entity decoding, session-key/CRC/length handling, and publishes the
`lightEffects` list so this app can discover the bulb's patterns.

**Updates:** HPM's **Update** action compares the `version` in the manifest against
what's installed. To publish a new version, bump `version` and `dateReleased` in
`packageManifest.json`, push, and HPM users will be offered the update.

**Optional — make it searchable in HPM:** "Install from a URL" works for you and anyone
you share the manifest URL with. To have it show up in HPM's **search/browse**, the
package must be listed in an HPM *repository* that the community index tracks (submitted
to the HPM project). That step is optional and only needed for public discoverability.

## Use

1. Open the app instance.
2. **Select the bulbs** to control (only color bulbs are listed).
3. Use **Turn All ON / OFF**.
4. **Set a common color** — pick a named color, enter custom Hue/Saturation/Brightness,
   or set a white color temperature.
5. **Light patterns (effects)** — the app reads each bulb's advertised effects and
   lets you apply a common one, choose per-bulb, or cycle next/previous.
6. **Scenes** — set the bulbs how you like, name a scene, and **Save scene**. Recall
   or delete saved scenes from the same page.
7. **Color show** — pick **Random** (same color on all, or a different one per bulb)
   or **Rotate** (cycle through chosen named colors), set the interval, and **Start**.
   It keeps running until you **Stop** it (and survives app edits/hub reboots).
8. **Automations** — turn on / apply a scene / apply a named color when **motion** is
   detected (with an optional auto-off delay after motion stops), and run a daily
   **time** action (on / off / apply scene). Press **Done** to arm them.
9. The **Current status** table on the main page shows live power/level/effect state.

## Notes & conventions

- Hubitat uses **0–100** for hue and saturation (percent), not 0–360 degrees.
- Color temperature range defaults to 2000–6500 K; individual bulbs clamp to their
  own supported range.
- Enable **debug logging** on the main page while testing; turn it off for daily use.

## Behavior notes for the new features

- **Scenes** are stored in the app's `state` keyed by a sanitized name. Recall matches
  bulbs by device id against your *current* selection — if a scene references a bulb
  you later removed from the app, that bulb is skipped. Effects are re-resolved by name
  at recall time, so they still work if a driver renumbers its effect ids.
- **Color show** self-reschedules with `runIn` and is marked running in `state`, so it
  resumes automatically after you save the app or the hub reboots. Interval is clamped
  to a 5-second minimum to protect your Zigbee/Z-Wave mesh from flooding.
- **Automations** are (re)armed in `initialize()` whenever you press **Done**. The motion
  auto-off only fires when *every* subscribed sensor is inactive, and is cancelled if
  motion resumes before the delay elapses.

## Roadmap ideas

- Per-bulb scene editing (currently scenes capture live state).
- Rotate mode using custom HSL colors, not just named presets.
- Trigger the color show from motion/time (currently on/color/scene only).
