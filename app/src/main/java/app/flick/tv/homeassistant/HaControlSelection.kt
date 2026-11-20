package app.flick.tv.homeassistant

import java.text.Collator

internal enum class HaEntityGroup(val title: String, vararg val domains: String) {
    Selected("Selected"),
    Cameras("Cameras", "camera"),
    Lights("Lights", "light"),
    Switches("Switches & helpers", "switch", "input_boolean"),
    Actions("Scenes & scripts", "scene", "script"),
    Sensors("Sensors", "sensor", "binary_sensor"),
}

internal fun groupControlEntities(entities: List<HaEntity>): Map<HaEntityGroup, List<HaControlSpec>> {
    val byDomain = entities.groupBy { it.domain }
    return buildMap {
        for (group in HaEntityGroup.entries) {
            val controls = group.domains.flatMap { byDomain[it].orEmpty() }
                .map { HaControlSpec(it.entityId, it.name) }
            if (controls.isNotEmpty()) put(group, controls.alphabetical())
        }
    }
}

internal fun List<HaControlSpec>.alphabetical(): List<HaControlSpec> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return sortedWith(compareBy<HaControlSpec, String>(collator) { it.name }.thenBy { it.entityId })
}

internal fun ambiguousControlIds(controls: List<HaControlSpec>): Set<String> =
    controls.groupBy { it.name.lowercase() }.values.filter { it.size > 1 }
        .flatten().mapTo(mutableSetOf()) { it.entityId }

/** Keep saved controls in place and append newly selected devices. */
internal fun selectedControlSpecs(entities: List<HaEntity>, selected: Set<String>, existing: List<HaControlSpec>): List<HaControlSpec> {
    val retained = existing.filter { it.entityId in selected }
    val added = entities.filter { it.entityId in selected }.map { HaControlSpec(it.entityId, it.name) }
    return (retained + added).distinctBy { it.entityId }
}

internal fun toggleControlSelection(selected: Set<String>, entityId: String): Set<String>? = when {
    entityId in selected -> selected - entityId
    selected.size < HaPolicy.maxControls -> selected + entityId
    else -> null
}

internal fun changeControlSelection(current: List<HaControlSpec>, control: HaControlSpec, enabled: Boolean): List<HaControlSpec> {
    if (!enabled) return current.filterNot { it.entityId == control.entityId }
    if (current.any { it.entityId == control.entityId }) return current
    return HaPolicy.controls(current + control)
}
