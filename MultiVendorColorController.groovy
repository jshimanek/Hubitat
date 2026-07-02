/**
 *  Multi-Vendor Color Bulb Controller
 *
 *  A Hubitat app to control color bulbs from different manufacturers together:
 *    - Turn all selected bulbs on / off
 *    - Apply one common color (or white temperature) to every selected bulb
 *    - Discover and apply manufacturer-defined "light patterns" (effects) that
 *      each device advertises through the standard `lightEffects` capability
 *    - Save & restore scenes (color + effect + power, per bulb)
 *    - Run a color "show": random or rotating colors on an interval
 *    - Automate via motion sensors and a daily time trigger
 *
 *  Because Hubitat exposes every vendor's bulb through the same capability
 *  interface, this app works across brands (LIFX, Sengled, Inovelli, generic
 *  Zigbee/Z-Wave RGBW, etc.) as long as the device driver reports the matching
 *  capability. Bulbs that support built-in patterns publish them via the
 *  `lightEffects` attribute, which this app reads on demand.
 *
 *  Author: shimanek@adobe.com
 *  License: Apache-2.0
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final List<Map> NAMED_COLORS = [
    [name: "Red",        hue: 0,   sat: 100],
    [name: "Orange",     hue: 8,   sat: 100],
    [name: "Yellow",     hue: 16,  sat: 100],
    [name: "Green",      hue: 33,  sat: 100],
    [name: "Cyan",       hue: 50,  sat: 100],
    [name: "Blue",       hue: 66,  sat: 100],
    [name: "Purple",     hue: 75,  sat: 100],
    [name: "Magenta",    hue: 83,  sat: 100],
    [name: "Pink",       hue: 90,  sat: 50],
    [name: "White",      hue: 0,   sat: 0]
]

definition(
    name:        "Multi-Vendor Color Bulb Controller",
    namespace:   "shimanek",
    author:      "shimanek@adobe.com",
    description: "Control color bulbs from different manufacturers together: on/off, common color, patterns, scenes, color shows, and automations.",
    category:    "Lighting",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
    page(name: "colorPage")
    page(name: "effectsPage")
    page(name: "scenesPage")
    page(name: "showPage")
    page(name: "automationPage")
}

/* ============================ Lifecycle ============================ */

def installed() {
    log.info "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.info "Updated with settings: ${settings}"
    initialize()
}

def initialize() {
    // Clear all prior subscriptions and schedules, then rebuild them.
    unsubscribe()
    unschedule()

    // Motion automations
    if (motionEnable && motionSensors) {
        subscribe(motionSensors, "motion.active", "motionActiveHandler")
        if (motionOffEnable) {
            subscribe(motionSensors, "motion.inactive", "motionInactiveHandler")
        }
        log.info "Subscribed to ${motionSensors.size()} motion sensor(s)"
    }

    // Daily time trigger
    if (timeEnable && triggerTime) {
        schedule(triggerTime, "timeTriggerHandler")
        log.info "Scheduled daily time trigger at ${triggerTime}"
    }

    // Resume the color show if it was running before this reconfigure.
    if (state.showRunning) {
        runShowStep()
    }
}

def uninstalled() {
    log.info "Uninstalled ${app.label}"
}

/* ============================ Main Page ============================ */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Multi-Vendor Color Bulb Controller", install: true, uninstall: true) {

        section("Select the bulbs to control") {
            input name: "bulbs",
                  type: "capability.colorControl",
                  title: "Color bulbs (any manufacturer)",
                  multiple: true,
                  required: false,
                  submitOnChange: true
        }

        if (bulbs) {
            section("Power") {
                input name: "btnAllOn",  type: "button", title: "Turn All ON"
                input name: "btnAllOff", type: "button", title: "Turn All OFF"
            }

            section("Color, effects, scenes & shows") {
                href name: "toColorPage",   page: "colorPage",   title: "Set a common color",       description: "Pick one color/white for every selected bulb"
                href name: "toEffectsPage", page: "effectsPage", title: "Light patterns (effects)", description: "Discover & apply manufacturer patterns"
                href name: "toScenesPage",  page: "scenesPage",  title: "Scenes",                   description: sceneSummary()
                href name: "toShowPage",    page: "showPage",    title: "Color show",               description: showSummary()
                href name: "toAutoPage",    page: "automationPage", title: "Automations",           description: automationSummary()
            }

            section("Current status") {
                paragraph buildStatusTable()
            }
        } else {
            section {
                paragraph "Select one or more color bulbs above to get started."
            }
        }

        section {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            label title: "Name this app instance", required: false
        }
    }
}

/* ============================ Color Page ============================ */

def colorPage() {
    dynamicPage(name: "colorPage", title: "Set a common color") {

        section("Preset colors") {
            input name: "presetColor",
                  type: "enum",
                  title: "Named color",
                  options: NAMED_COLORS.collect { it.name },
                  required: false,
                  submitOnChange: false
            input name: "btnApplyPreset", type: "button", title: "Apply named color to all"
        }

        section("Custom color (HSL)") {
            input name: "customHue",  type: "number", title: "Hue (0-100)",        range: "0..100", required: false
            input name: "customSat",  type: "number", title: "Saturation (0-100)", range: "0..100", required: false
            input name: "customLevel",type: "number", title: "Brightness (1-100)", range: "1..100", required: false
            input name: "btnApplyCustom", type: "button", title: "Apply custom color to all"
        }

        section("White / color temperature") {
            input name: "colorTemp", type: "number", title: "Color temperature (2000-6500 K)", range: "2000..6500", required: false
            input name: "btnApplyTemp", type: "button", title: "Apply white temperature to all"
            paragraph "Note: only bulbs that report the colorTemperature capability will respond to white temperature."
        }
    }
}

/* ============================ Effects Page ============================ */

def effectsPage() {
    dynamicPage(name: "effectsPage", title: "Light patterns (effects)") {

        Map<String, List<String>> deviceEffects = readAllEffects()
        List<String> common = commonEffects(deviceEffects)

        section("Common patterns (supported by ALL selected bulbs)") {
            if (common) {
                input name: "commonEffect",
                      type: "enum",
                      title: "Choose a pattern supported by every bulb",
                      options: common,
                      required: false
                input name: "btnApplyCommon", type: "button", title: "Apply to all bulbs"
            } else {
                paragraph "No single pattern is supported by every selected bulb. Use the per-bulb section below, or select bulbs from the same product family."
            }
        }

        section("Per-bulb patterns (what each device advertises)") {
            bulbs.each { dev ->
                List<String> fx = deviceEffects[dev.id] ?: []
                if (fx) {
                    input name: "effect_${dev.id}",
                          type: "enum",
                          title: "${dev.displayName}",
                          options: fx,
                          required: false
                } else {
                    paragraph "${dev.displayName}: no light-pattern support reported (no lightEffects capability)."
                }
            }
            input name: "btnApplyPerBulb", type: "button", title: "Apply each selected pattern"
        }

        section("Cycle") {
            input name: "btnNextEffect", type: "button", title: "Next pattern (all)"
            input name: "btnPrevEffect", type: "button", title: "Previous pattern (all)"
        }
    }
}

/* ============================ Scenes Page ============================ */

def scenesPage() {
    dynamicPage(name: "scenesPage", title: "Scenes") {

        section("Save current state as a scene") {
            paragraph "Captures each selected bulb's power, color, and current effect. Recall it later, or trigger it from motion / time automations."
            input name: "newSceneName", type: "text", title: "Scene name", required: false, submitOnChange: false
            input name: "btnSaveScene", type: "button", title: "Save scene"
        }

        section("Saved scenes") {
            Map scenes = state.scenes ?: [:]
            if (scenes.isEmpty()) {
                paragraph "No scenes saved yet."
            } else {
                scenes.each { key, scene ->
                    paragraph "<b>${scene.displayName}</b> — ${scene.devices?.size() ?: 0} bulb(s)"
                    input name: "sceneApply_${key}", type: "button", title: "Apply '${scene.displayName}'"
                    input name: "sceneDel_${key}",   type: "button", title: "Delete '${scene.displayName}'"
                }
            }
        }
    }
}

/* ============================ Color Show Page ============================ */

def showPage() {
    dynamicPage(name: "showPage", title: "Color show") {

        section("Mode") {
            input name: "showMode", type: "enum", title: "Show mode",
                  options: ["Random", "Rotate"], required: false, submitOnChange: true
            input name: "showInterval", type: "number", title: "Change every (seconds, min 5)",
                  range: "5..3600", defaultValue: 30, required: false
            input name: "showLevel", type: "number", title: "Brightness (1-100)",
                  range: "1..100", defaultValue: 100, required: false
        }

        if (showMode == "Random") {
            section("Random options") {
                input name: "showSameForAll", type: "bool",
                      title: "Same random color on all bulbs (off = each bulb different)",
                      defaultValue: false
            }
        }

        if (showMode == "Rotate") {
            section("Rotate options") {
                input name: "showColors", type: "enum", title: "Colors to rotate through (default: all)",
                      options: NAMED_COLORS.collect { it.name }, multiple: true, required: false
            }
        }

        section("Control") {
            input name: "btnStartShow", type: "button", title: "Start show"
            input name: "btnStopShow",  type: "button", title: "Stop show"
            paragraph state.showRunning ? "Status: <b>running</b> (${showMode})" : "Status: stopped"
        }
    }
}

/* ============================ Automations Page ============================ */

def automationPage() {
    dynamicPage(name: "automationPage", title: "Automations") {

        section("Motion trigger") {
            input name: "motionEnable", type: "bool", title: "Enable motion trigger", defaultValue: false, submitOnChange: true
            if (motionEnable) {
                input name: "motionSensors", type: "capability.motionSensor", title: "Motion sensors", multiple: true, required: false
                input name: "motionAction", type: "enum", title: "When motion is detected",
                      options: ["Turn on", "Turn on + apply scene", "Apply named color"],
                      defaultValue: "Turn on", required: false, submitOnChange: true
                if (motionAction == "Turn on + apply scene") {
                    input name: "motionScene", type: "enum", title: "Scene", options: sceneOptions(), required: false
                }
                if (motionAction == "Apply named color") {
                    input name: "motionColor", type: "enum", title: "Named color", options: NAMED_COLORS.collect { it.name }, required: false
                }
                input name: "motionOffEnable", type: "bool", title: "Turn off after motion stops", defaultValue: false, submitOnChange: true
                if (motionOffEnable) {
                    input name: "motionOffDelay", type: "number", title: "Off delay (minutes)", range: "1..240", defaultValue: 5, required: false
                }
            }
        }

        section("Time trigger (daily)") {
            input name: "timeEnable", type: "bool", title: "Enable time trigger", defaultValue: false, submitOnChange: true
            if (timeEnable) {
                input name: "triggerTime", type: "time", title: "Time of day", required: false
                input name: "timeAction", type: "enum", title: "Action",
                      options: ["Turn all on", "Turn all off", "Apply scene"],
                      defaultValue: "Turn all on", required: false, submitOnChange: true
                if (timeAction == "Apply scene") {
                    input name: "timeScene", type: "enum", title: "Scene", options: sceneOptions(), required: false
                }
            }
        }

        section {
            paragraph "Automations activate when you press Done to save the app. Changes here re-arm subscriptions and schedules."
        }
    }
}

/* ============================ Button handling ============================ */

def appButtonHandler(String btn) {
    logDebug "Button pressed: ${btn}"

    if (btn.startsWith("sceneApply_")) { applyScene(btn.substring("sceneApply_".length())); return }
    if (btn.startsWith("sceneDel_"))   { deleteScene(btn.substring("sceneDel_".length())); return }

    switch (btn) {
        case "btnAllOn":       allOn();               break
        case "btnAllOff":      allOff();              break
        case "btnApplyPreset": applyPresetColor();    break
        case "btnApplyCustom": applyCustomColor();    break
        case "btnApplyTemp":   applyColorTemp();      break
        case "btnApplyCommon": applyCommonEffect();   break
        case "btnApplyPerBulb":applyPerBulbEffects(); break
        case "btnNextEffect":  cycleEffect(true);     break
        case "btnPrevEffect":  cycleEffect(false);    break
        case "btnSaveScene":   saveScene();           break
        case "btnStartShow":   startShow();           break
        case "btnStopShow":    stopShow();            break
        default: log.warn "Unhandled button: ${btn}"
    }
}

/* ============================ Basic actions ============================ */

private allOn() {
    bulbs?.each { it.on() }
    log.info "Turned ${bulbs?.size() ?: 0} bulb(s) ON"
}

private allOff() {
    bulbs?.each { it.off() }
    log.info "Turned ${bulbs?.size() ?: 0} bulb(s) OFF"
}

private applyColorMapToAll(Map colorMap) {
    bulbs?.each { it.setColor(colorMap) }
}

private applyPresetColor() {
    if (!presetColor) { log.warn "No named color selected"; return }
    Map c = NAMED_COLORS.find { it.name == presetColor }
    if (!c) { log.warn "Unknown named color: ${presetColor}"; return }
    Integer level = (customLevel != null) ? customLevel as Integer : 100
    Map colorMap = [hue: c.hue, saturation: c.sat, level: level]
    applyColorMapToAll(colorMap)
    log.info "Applied preset '${presetColor}' ${colorMap} to ${bulbs?.size() ?: 0} bulb(s)"
}

private applyCustomColor() {
    if (customHue == null || customSat == null) {
        log.warn "Custom color needs at least hue and saturation"
        return
    }
    Integer level = (customLevel != null) ? customLevel as Integer : 100
    Map colorMap = [hue: customHue as Integer, saturation: customSat as Integer, level: level]
    applyColorMapToAll(colorMap)
    log.info "Applied custom color ${colorMap} to ${bulbs?.size() ?: 0} bulb(s)"
}

private applyColorTemp() {
    if (colorTemp == null) { log.warn "No color temperature entered"; return }
    int count = 0
    bulbs?.each { dev ->
        if (dev.hasCapability("ColorTemperature")) {
            dev.setColorTemperature(colorTemp as Integer)
            count++
        }
    }
    log.info "Applied ${colorTemp}K to ${count} bulb(s) that support color temperature"
}

/* ============================ Effect actions ============================ */

private applyCommonEffect() {
    if (!commonEffect) { log.warn "No common effect selected"; return }
    bulbs?.each { dev ->
        Integer id = effectIdForName(dev, commonEffect)
        if (id != null) { dev.setEffect(id) }
    }
    log.info "Applied common effect '${commonEffect}'"
}

private applyPerBulbEffects() {
    bulbs?.each { dev ->
        String chosen = settings["effect_${dev.id}"]
        if (chosen) {
            Integer id = effectIdForName(dev, chosen)
            if (id != null) {
                dev.setEffect(id)
                logDebug "Set '${chosen}' (id ${id}) on ${dev.displayName}"
            }
        }
    }
    log.info "Applied per-bulb effects"
}

private cycleEffect(boolean forward) {
    bulbs?.each { dev ->
        if (forward && dev.hasCommand("setNextEffect")) {
            dev.setNextEffect()
        } else if (!forward && dev.hasCommand("setPreviousEffect")) {
            dev.setPreviousEffect()
        }
    }
    log.info "Cycled effects ${forward ? 'forward' : 'backward'}"
}

/* ============================ Scenes ============================ */

private saveScene() {
    if (!newSceneName) { log.warn "Enter a scene name first"; return }
    if (!bulbs)        { log.warn "No bulbs selected"; return }
    if (state.scenes == null) state.scenes = [:]
    String key = uniqueSceneKey(newSceneName)
    state.scenes[key] = [ displayName: newSceneName.trim(),
                          devices: bulbs.collect { captureDevice(it) } ]
    log.info "Saved scene '${newSceneName}' with ${bulbs.size()} bulb(s)"
    app.updateSetting("newSceneName", [value: "", type: "text"])
}

private applyScene(String key) {
    def scene = state.scenes?.get(key)
    if (!scene) { log.warn "Scene '${key}' not found"; return }
    scene.devices.each { entry ->
        def dev = bulbs?.find { it.id == entry.id }
        if (!dev) {
            logDebug "Scene bulb '${entry.name}' is not in the current selection; skipping"
            return
        }
        if (entry.switch == "off") {
            dev.off()
            return
        }
        dev.on()
        if (entry.effectName && !(entry.effectName in ["none", "n/a", "None", null])) {
            Integer id = effectIdForName(dev, entry.effectName as String)
            if (id != null) { dev.setEffect(id); return }
        }
        if (entry.hue != null && entry.saturation != null) {
            dev.setColor([hue: entry.hue as Integer,
                          saturation: entry.saturation as Integer,
                          level: (entry.level != null ? entry.level as Integer : 100)])
        }
    }
    log.info "Applied scene '${scene.displayName}'"
}

private deleteScene(String key) {
    if (state.scenes?.containsKey(key)) {
        String name = state.scenes[key].displayName
        state.scenes.remove(key)
        log.info "Deleted scene '${name}'"
    }
}

private Map captureDevice(dev) {
    [ id:         dev.id,
      name:       dev.displayName,
      switch:     safeAttr(dev, "switch"),
      hue:        numAttr(dev, "hue"),
      saturation: numAttr(dev, "saturation"),
      level:      numAttr(dev, "level"),
      effectName: safeAttr(dev, "effectName") ]
}

private String uniqueSceneKey(String name) {
    String base = name.trim().replaceAll(/[^A-Za-z0-9]/, "_")
    if (!base) base = "scene"
    String key = base
    int n = 1
    while (state.scenes?.containsKey(key)) { key = "${base}_${n++}" }
    return key
}

/** Returns scene options as {key: displayName} so settings store the stable key. */
private Map sceneOptions() {
    (state.scenes ?: [:]).collectEntries { k, v -> [(k): v.displayName] }
}

/* ============================ Color show ============================ */

private startShow() {
    if (!showMode) { log.warn "Choose Random or Rotate first"; return }
    if (!bulbs)    { log.warn "No bulbs selected"; return }
    state.showRunning = true
    state.rotateIndex = 0
    runShowStep()
    log.info "Color show started: ${showMode}"
}

private stopShow() {
    state.showRunning = false
    unschedule("runShowStep")
    log.info "Color show stopped"
}

def runShowStep() {
    if (!state.showRunning) return
    if (!bulbs) { stopShow(); return }

    Integer level = (showLevel != null) ? showLevel as Integer : 100

    if (showMode == "Random") {
        if (showSameForAll) {
            applyColorMapToAll(randomColor(level))
        } else {
            bulbs.each { it.setColor(randomColor(level)) }
        }
    } else if (showMode == "Rotate") {
        List<String> names = (showColors && showColors.size() > 0) ? showColors : NAMED_COLORS.collect { it.name }
        int idx = ((state.rotateIndex ?: 0) as Integer) % names.size()
        Map c = NAMED_COLORS.find { it.name == names[idx] }
        if (c) applyColorMapToAll([hue: c.hue, saturation: c.sat, level: level])
        state.rotateIndex = idx + 1
    }

    Integer interval = (showInterval != null) ? Math.max(5, showInterval as Integer) : 30
    runIn(interval, "runShowStep", [overwrite: true])
}

private Map randomColor(Integer level) {
    [hue: (int) (Math.random() * 101), saturation: 100, level: level]
}

/* ============================ Automation handlers ============================ */

def motionActiveHandler(evt) {
    logDebug "Motion active from ${evt.displayName}"
    unschedule("motionOffAction")   // cancel any pending off
    switch (motionAction) {
        case "Turn on + apply scene":
            allOn()
            if (motionScene) applyScene(motionScene)
            break
        case "Apply named color":
            allOn()
            applyNamedColor(motionColor)
            break
        default: // "Turn on"
            allOn()
    }
}

def motionInactiveHandler(evt) {
    if (!motionOffEnable) return
    // Only schedule the off if no other subscribed sensor is still active.
    boolean anyActive = motionSensors?.any { it.currentValue("motion") == "active" }
    if (anyActive) return
    Integer mins = (motionOffDelay != null) ? motionOffDelay as Integer : 5
    runIn(mins * 60, "motionOffAction", [overwrite: true])
    logDebug "Scheduled lights-off in ${mins} minute(s)"
}

def motionOffAction() {
    boolean anyActive = motionSensors?.any { it.currentValue("motion") == "active" }
    if (anyActive) { logDebug "Motion resumed; skipping off"; return }
    allOff()
}

def timeTriggerHandler() {
    logDebug "Time trigger fired: ${timeAction}"
    switch (timeAction) {
        case "Turn all off": allOff(); break
        case "Apply scene":  if (timeScene) applyScene(timeScene); break
        default:             allOn()
    }
}

private applyNamedColor(String name) {
    if (!name) { log.warn "No named color set"; return }
    Map c = NAMED_COLORS.find { it.name == name }
    if (!c) { log.warn "Unknown named color: ${name}"; return }
    Integer level = (customLevel != null) ? customLevel as Integer : 100
    applyColorMapToAll([hue: c.hue, saturation: c.sat, level: level])
}

/* ============================ Effect discovery helpers ============================ */

/**
 * Reads the `lightEffects` attribute for every selected bulb.
 * The attribute is a JSON map of {id: "Effect Name"} published by each driver.
 * Returns a map of deviceId -> sorted list of effect names.
 */
private Map<String, List<String>> readAllEffects() {
    Map<String, List<String>> result = [:]
    bulbs?.each { dev ->
        result[dev.id] = effectMapFor(dev).values().toList().sort()
    }
    return result
}

/** Returns a map of {id(String) -> name(String)} of effects a single device advertises. */
private Map effectMapFor(dev) {
    String raw = null
    try {
        raw = dev.currentValue("lightEffects")
    } catch (ignored) {
        raw = null
    }
    if (!raw) return [:]
    try {
        def parsed = new JsonSlurper().parseText(raw)
        Map out = [:]
        parsed.each { k, v -> out[k.toString()] = v.toString() }
        return out
    } catch (Exception e) {
        logDebug "Could not parse lightEffects for ${dev.displayName}: ${e.message}"
        return [:]
    }
}

/** Effect names supported by every selected bulb (intersection). */
private List<String> commonEffects(Map<String, List<String>> deviceEffects) {
    if (!deviceEffects) return []
    List<List<String>> lists = deviceEffects.values().findAll { it }.toList()
    if (lists.isEmpty()) return []
    Set<String> common = new HashSet<>(lists[0])
    lists.drop(1).each { common.retainAll(it) }
    return common.toList().sort()
}

/** Resolves the numeric effect id for a given effect name on a specific device. */
private Integer effectIdForName(dev, String name) {
    Map fx = effectMapFor(dev)
    def entry = fx.find { k, v -> v == name }
    if (entry == null) {
        logDebug "'${name}' not found on ${dev.displayName}"
        return null
    }
    try {
        return entry.key as Integer
    } catch (Exception e) {
        log.warn "Effect id '${entry.key}' on ${dev.displayName} is not numeric"
        return null
    }
}

/* ============================ Status & summaries ============================ */

private String buildStatusTable() {
    if (!bulbs) return "No bulbs selected."
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%;border-collapse:collapse;'>")
    sb.append("<tr style='text-align:left;border-bottom:1px solid #ccc;'>")
    sb.append("<th>Bulb</th><th>Power</th><th>Level</th><th>Effect</th></tr>")
    bulbs.each { dev ->
        String sw    = safeAttr(dev, "switch")
        String lvl   = safeAttr(dev, "level")
        String fxNow = safeAttr(dev, "effectName")
        sb.append("<tr style='border-bottom:1px solid #eee;'>")
        sb.append("<td>${dev.displayName}</td>")
        sb.append("<td>${sw}</td>")
        sb.append("<td>${lvl == 'n/a' ? lvl : lvl + '%'}</td>")
        sb.append("<td>${fxNow}</td>")
        sb.append("</tr>")
    }
    sb.append("</table>")
    return sb.toString()
}

private String sceneSummary() {
    int n = (state.scenes ?: [:]).size()
    return n == 0 ? "Save & recall color/effect scenes" : "${n} scene(s) saved"
}

private String showSummary() {
    return state.showRunning ? "Running (${showMode})" : "Random or rotating colors on a timer"
}

private String automationSummary() {
    List<String> parts = []
    if (motionEnable && motionSensors) parts << "motion"
    if (timeEnable && triggerTime)     parts << "daily time"
    return parts.isEmpty() ? "Trigger from motion or time of day" : "Active: ${parts.join(', ')}"
}

private String safeAttr(dev, String attr) {
    try {
        def v = dev.currentValue(attr)
        return (v != null) ? v.toString() : "n/a"
    } catch (ignored) {
        return "n/a"
    }
}

private Integer numAttr(dev, String attr) {
    try {
        def v = dev.currentValue(attr)
        return (v != null) ? (v as Integer) : null
    } catch (ignored) {
        return null
    }
}

/* ============================ Logging ============================ */

private logDebug(String msg) {
    if (logEnable) log.debug msg
}
