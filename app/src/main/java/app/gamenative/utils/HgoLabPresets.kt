package app.gamenative.utils

import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import java.util.Locale

object HgoLabPresets {
    const val CHEAT_HELPER = "cheat-helper"
    const val DQH_VULKAN_VIDEO = "dqh-vulkan-video"

    data class Preset(
        val id: String,
        val label: String,
        val description: String,
        val aliases: Set<String> = emptySet(),
        val envVars: Map<String, String> = emptyMap(),
        val explicitKeys: Set<String> = emptySet(),
        val apply: (ContainerData) -> ContainerData = { it },
    )

    val presets = listOf(
        Preset(
            id = CHEAT_HELPER,
            label = "Cheat helper + controller DLLs",
            description = "Loads dinput8/xinput helper DLLs and enables handheld controller APIs.",
            aliases = setOf("cheats", "cheat", "helper", "overlay", "tokyo", "zwei", "dqh-cheats"),
            envVars = mapOf(
                "WINEDLLOVERRIDES" to "dinput8,xinput1_4,xinput1_3,xinput9_1_0=n,b",
            ),
            explicitKeys = setOf(
                "envVars",
                "sdlControllerAPI",
                "useSteamInput",
                "enableXInput",
                "enableDInput",
                "dinputMapperType",
                "disableMouseInput",
            ),
            apply = { config ->
                config.copy(
                    sdlControllerAPI = true,
                    useSteamInput = false,
                    enableXInput = true,
                    enableDInput = true,
                    dinputMapperType = 1,
                    disableMouseInput = false,
                )
            },
        ),
        Preset(
            id = DQH_VULKAN_VIDEO,
            label = "DQH Vulkan + hardware video probe",
            description = "Uses the cheat helper path plus HGO MediaCodec H.264 and the visible DXVK/DRI3-off lane.",
            aliases = setOf("dqh", "dragon-quest-heroes", "dqheroes", "hardware-video", "mediacodec"),
            envVars = mapOf(
                "WINEDLLOVERRIDES" to "dinput8,xinput1_4,xinput1_3,xinput9_1_0=n,b",
                "HGO_GST_MEDIACODEC_ENABLE" to "1",
                "WINE_GST_NO_GL" to "0",
                "DXVK_ASYNC" to "1",
            ),
            explicitKeys = setOf(
                "envVars",
                "screenSize",
                "dxwrapper",
                "useDRI3",
                "sdlControllerAPI",
                "useSteamInput",
                "enableXInput",
                "enableDInput",
                "dinputMapperType",
                "disableMouseInput",
            ),
            apply = { config ->
                config.copy(
                    screenSize = "1280x720",
                    dxwrapper = "dxvk",
                    useDRI3 = false,
                    sdlControllerAPI = true,
                    useSteamInput = false,
                    enableXInput = true,
                    enableDInput = true,
                    dinputMapperType = 1,
                    disableMouseInput = false,
                )
            },
        ),
    )

    fun applyPreset(config: ContainerData, presetId: String?): ContainerData {
        val preset = findPreset(presetId) ?: return config
        val configWithEnv = config.copy(envVars = mergeEnvVars(config.envVars, preset.envVars))
        val applied = preset.apply(configWithEnv)
        return applied.copy(explicitOverrideKeys = applied.explicitOverrideKeys + preset.explicitKeys)
    }

    fun applyPresetList(config: ContainerData, presetIds: String?): ContainerData {
        if (presetIds.isNullOrBlank()) return config
        return presetIds
            .split(',', ';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .fold(config) { current, presetId -> applyPreset(current, presetId) }
    }

    fun findPreset(presetId: String?): Preset? {
        val normalized = normalizePresetId(presetId)
        if (normalized.isEmpty()) return null
        return presets.firstOrNull { preset ->
            normalizePresetId(preset.id) == normalized ||
                preset.aliases.any { normalizePresetId(it) == normalized }
        }
    }

    private fun mergeEnvVars(base: String, additions: Map<String, String>): String {
        val envVars = try {
            EnvVars(base)
        } catch (_: Exception) {
            EnvVars()
        }
        additions.forEach { (name, value) -> envVars.put(name, value) }
        return envVars.toString()
    }

    private fun normalizePresetId(presetId: String?): String {
        return presetId.orEmpty()
            .trim()
            .replace("_", "-")
            .lowercase(Locale.ROOT)
    }
}
