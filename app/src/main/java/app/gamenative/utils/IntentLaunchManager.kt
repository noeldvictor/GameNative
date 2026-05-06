package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.util.Base64
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.container.ContainerData
import java.io.File
import com.winlator.core.DXVKHelper
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles external game launch intents with container configuration overrides.
 */
object IntentLaunchManager {

    private const val EXTRA_APP_ID = "app_id"

    private const val EXTRA_GAME_SOURCE = "game_source"
    private const val EXTRA_CONTAINER_CONFIG = "container_config"
    private const val EXTRA_CONTAINER_CONFIG_B64 = "container_config_b64"
    private const val EXTRA_HGO_LAB_PRESET = "hgo_lab_preset"
    private const val EXTRA_HGO_LAB_PRESET_CAMEL = "hgoLabPreset"
    private const val EXTRA_LAB_PRESET = "lab_preset"
    private const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"
    private const val MAX_CONFIG_JSON_SIZE = 50000 // 50KB limit to prevent memory exhaustion

    data class LaunchRequest(
        val appId: String,
        val containerConfig: ContainerData? = null,
    )

    fun parseLaunchIntent(intent: Intent): LaunchRequest? {
        Timber.d("[IntentLaunchManager]: Parsing intent: action=${intent.action}")

        if (intent.action != ACTION_LAUNCH_GAME) {
            Timber.d("[IntentLaunchManager]: Intent action '${intent.action}' doesn't match expected action '$ACTION_LAUNCH_GAME'")
            return null
        }

        val gameId = intent.getIntExtra(EXTRA_APP_ID, -1)
        Timber.d("[IntentLaunchManager]: Extracted app_id: $gameId from intent extras")

        if (gameId <= 0) {
            Timber.w("[IntentLaunchManager]: Invalid or missing app_id in launch intent: $gameId")
            return null
        }

        // Get Game Source for launch intent
        var gameSource = intent.getStringExtra(EXTRA_GAME_SOURCE)?.uppercase(java.util.Locale.ROOT)
        val isValidGameSource = GameSource.entries.any { it.name == gameSource }
        if (!isValidGameSource) {
            gameSource = GameSource.STEAM.name
        }

        val appId = "${gameSource}_$gameId"
        Timber.d("[IntentLaunchManager]: Converted to appId: $appId")

        val containerConfigJson = intent.getStringExtra(EXTRA_CONTAINER_CONFIG)
            ?: intent.getStringExtra(EXTRA_CONTAINER_CONFIG_B64)?.let { encoded ->
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }
        var containerConfig = if (containerConfigJson != null) {
            try {
                parseContainerConfig(containerConfigJson)
            } catch (e: Exception) {
                Timber.e(e, "[IntentLaunchManager]: Failed to parse container configuration JSON")
                null
            }
        } else {
            null
        }
        val labPreset = intent.getStringExtra(EXTRA_HGO_LAB_PRESET)
            ?: intent.getStringExtra(EXTRA_HGO_LAB_PRESET_CAMEL)
            ?: intent.getStringExtra(EXTRA_LAB_PRESET)
        if (!labPreset.isNullOrBlank()) {
            containerConfig = HgoLabPresets.applyPresetList(containerConfig ?: ContainerData(), labPreset)
            Timber.i("[IntentLaunchManager]: Applied HGO lab preset(s) from launch intent: $labPreset")
        }

        return LaunchRequest(appId, containerConfig)
    }

    fun applyTemporaryConfigOverride(context: Context, appId: String, configOverride: ContainerData) {
        try {
            TemporaryConfigStore.setOverride(appId, configOverride)

            if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)

                // Backup original config before applying override (only once)
                if (TemporaryConfigStore.getOriginalConfig(appId) == null) {
                    val originalConfig = ContainerUtils.toContainerData(container)
                    TemporaryConfigStore.setOriginalConfig(appId, originalConfig)
                }

                // Get the effective config (merge base with override)
                val effectiveConfig = getEffectiveContainerConfig(context, appId)
                if (effectiveConfig != null) {
                    ContainerUtils.applyToContainer(context, container, effectiveConfig, saveToDisk = false)
                    Timber.i("[IntentLaunchManager]: Applied temporary config override for app $appId (in-memory only)")
                }
            } else {
                Timber.i("[IntentLaunchManager]: Stored temporary config override for app $appId (container will be created on launch)")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to apply temporary config override for app $appId")
            throw e
        }
    }

    fun registerCustomGameFromLaunchIntent(appId: String, configOverride: ContainerData?) {
        if (!appId.startsWith("${GameSource.CUSTOM_GAME.name}_") || configOverride == null) {
            return
        }

        val installPath = configOverride.installPath.trim()
        if (installPath.isEmpty()) {
            return
        }

        val idPart = appId.removePrefix("${GameSource.CUSTOM_GAME.name}_").toIntOrNull()
        if (idPart == null || idPart <= 0) {
            Timber.w("[IntentLaunchManager]: Cannot register Custom Game from invalid appId: $appId")
            return
        }

        val folder = File(installPath)
        if (!folder.exists() || !folder.isDirectory) {
            Timber.w("[IntentLaunchManager]: Cannot register missing Custom Game folder: $installPath")
            return
        }

        val folders = PrefManager.customGameManualFolders.toMutableSet()
        if (folders.add(folder.absolutePath)) {
            PrefManager.customGameManualFolders = folders
            Timber.i("[IntentLaunchManager]: Added Custom Game folder from launch intent: ${folder.absolutePath}")
        }

        GameMetadataManager.update(folder, appId = idPart)
        CustomGameScanner.invalidateCache()
    }

    fun getEffectiveContainerConfig(context: Context, appId: String): ContainerData? {
        return try {
            val baseConfig = if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.toContainerData(container)
            } else {
                null
            }

            val override = TemporaryConfigStore.getOverride(appId)
            val normalizedBase = baseConfig?.withLaunchDefaults()

            when {
                override != null && normalizedBase != null -> mergeConfigurations(normalizedBase, override)
                override != null -> override.withLaunchDefaults()
                else -> normalizedBase
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to get effective container config for app $appId")
            null
        }
    }

    fun clearTemporaryOverride(appId: String) {
        TemporaryConfigStore.clearOverride(appId)
        Timber.d("[IntentLaunchManager]: Cleared temporary config override for app $appId")
    }

    fun clearAllTemporaryOverrides() {
        TemporaryConfigStore.clearAll()
        Timber.d("[IntentLaunchManager]: Cleared all temporary config overrides")
    }

    fun restoreOriginalConfiguration(context: Context, appId: String) {
        try {
            val originalConfig = TemporaryConfigStore.getOriginalConfig(appId)
            if (originalConfig != null && ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.applyToContainer(context, container, originalConfig, saveToDisk = false)
                Timber.i("[IntentLaunchManager]: Restored original configuration for app $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to restore original configuration for app $appId")
        }
    }

    fun hasTemporaryOverride(appId: String): Boolean {
        return TemporaryConfigStore.hasOverride(appId)
    }

    fun getTemporaryOverride(appId: String): ContainerData? {
        return TemporaryConfigStore.getOverride(appId)
    }

    fun getOriginalConfig(appId: String): ContainerData? {
        return TemporaryConfigStore.getOriginalConfig(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) {
        TemporaryConfigStore.setOriginalConfig(appId, config)
    }

    private fun validateContainerConfig(config: ContainerData): List<String> {
        val issues = mutableListOf<String>()

        if (!config.screenSize.matches(Regex("\\d+x\\d+"))) {
            issues.add("Invalid screen size format: ${config.screenSize}. Expected format: WIDTHxHEIGHT (e.g., 1920x1080)")
        }

        if (config.cpuList.isNotEmpty() && !config.cpuList.matches(Regex("\\d+(,\\d+)*"))) {
            issues.add("Invalid CPU list format: ${config.cpuList}. Expected comma-separated numbers (e.g., 0,1,2,3)")
        }

        if (!config.videoMemorySize.matches(Regex("\\d+"))) {
            issues.add("Invalid video memory size: ${config.videoMemorySize}. Expected numeric value in MB")
        }

        if (config.drives.isNotEmpty() && !config.drives.matches(Regex("([A-Z]:([^:]+))*"))) {
            issues.add("Invalid drives format: ${config.drives}. Expected format: LETTER:PATH (e.g., C:/path/to/drive)")
        }

        return issues
    }

    private fun parseContainerConfig(jsonString: String): ContainerData {
        if (jsonString.length > MAX_CONFIG_JSON_SIZE) {
            throw IllegalArgumentException("Container configuration JSON too large (max ${MAX_CONFIG_JSON_SIZE / 1000}KB)")
        }

        val json = JSONObject(jsonString)

        val explicitKeys = json.keys().asSequence().toSet()
        val hgoLabPreset = when {
            json.has("hgoLabPreset") -> json.getString("hgoLabPreset")
            json.has("hgo_lab_preset") -> json.getString("hgo_lab_preset")
            json.has("labPreset") -> json.getString("labPreset")
            json.has("lab_preset") -> json.getString("lab_preset")
            else -> ""
        }

        // Defaults here are used for validation and new-container launches. When merging
        // with an existing container, explicitKeys decides whether a default-valued field
        // such as dxvk or useDRI3=true should still override the saved profile.
        var config = ContainerData(
            name = if (json.has("name")) json.getString("name") else "",
            screenSize = if (json.has("screenSize")) json.getString("screenSize") else Container.DEFAULT_SCREEN_SIZE,
            envVars = if (json.has("envVars")) json.getString("envVars") else Container.DEFAULT_ENV_VARS,
            graphicsDriver = if (json.has("graphicsDriver")) json.getString("graphicsDriver") else Container.DEFAULT_GRAPHICS_DRIVER,
            graphicsDriverVersion = if (json.has("graphicsDriverVersion")) json.getString("graphicsDriverVersion") else "",
            graphicsDriverConfig = if (json.has("graphicsDriverConfig")) {
                json.getString("graphicsDriverConfig")
            } else {
                ""
            },
            dxwrapper = if (json.has("dxwrapper")) json.getString("dxwrapper") else Container.DEFAULT_DXWRAPPER,
            dxwrapperConfig = if (json.has("dxwrapperConfig")) {
                normalizeDxWrapperConfig(json.getString("dxwrapperConfig"))
            } else {
                ""
            },
            audioDriver = if (json.has("audioDriver")) json.getString("audioDriver") else Container.DEFAULT_AUDIO_DRIVER,
            wincomponents = if (json.has("wincomponents")) json.getString("wincomponents") else Container.DEFAULT_WINCOMPONENTS,
            drives = if (json.has("drives")) json.getString("drives") else Container.DEFAULT_DRIVES,
            execArgs = if (json.has("execArgs")) json.getString("execArgs") else "",
            executablePath = if (json.has("executablePath")) json.getString("executablePath") else "",
            installPath = if (json.has("installPath")) json.getString("installPath") else "",
            showFPS = if (json.has("showFPS")) json.getBoolean("showFPS") else false,
            launchRealSteam = if (json.has("launchRealSteam")) json.getBoolean("launchRealSteam") else false,
            allowSteamUpdates = if (json.has("allowSteamUpdates")) json.getBoolean("allowSteamUpdates") else false,
            steamType = if (json.has("steamType")) json.getString("steamType") else "normal",
            cpuList = if (json.has("cpuList")) json.getString("cpuList") else Container.getFallbackCPUList(),
            cpuListWoW64 = if (json.has("cpuListWoW64")) json.getString("cpuListWoW64") else Container.getFallbackCPUListWoW64(),
            wow64Mode = if (json.has("wow64Mode")) json.getBoolean("wow64Mode") else true,
            startupSelection = if (json.has("startupSelection")) {
                json.getInt("startupSelection").toByte()
            } else {
                Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte()
            },
            box86Version = if (json.has("box86Version")) json.getString("box86Version") else "",
            box64Version = if (json.has("box64Version")) json.getString("box64Version") else "",
            box86Preset = if (json.has("box86Preset")) json.getString("box86Preset") else "",
            box64Preset = if (json.has("box64Preset")) json.getString("box64Preset") else "",
            desktopTheme = if (json.has("desktopTheme")) json.getString("desktopTheme") else "",
            containerVariant = if (json.has("containerVariant")) json.getString("containerVariant") else Container.DEFAULT_VARIANT,
            wineVersion = if (json.has("wineVersion")) json.getString("wineVersion") else "",
            emulator = if (json.has("emulator")) json.getString("emulator") else Container.DEFAULT_EMULATOR,
            fexcoreVersion = if (json.has("fexcoreVersion")) json.getString("fexcoreVersion") else "",
            fexcoreTSOMode = if (json.has("fexcoreTSOMode")) json.getString("fexcoreTSOMode") else "Fast",
            fexcoreX87Mode = if (json.has("fexcoreX87Mode")) json.getString("fexcoreX87Mode") else "Fast",
            fexcoreMultiBlock = if (json.has("fexcoreMultiBlock")) json.getString("fexcoreMultiBlock") else "Disabled",
            fexcorePreset = if (json.has("fexcorePreset")) json.getString("fexcorePreset") else "",
            renderer = if (json.has("renderer")) json.getString("renderer") else "gl",
            csmt = if (json.has("csmt")) json.getBoolean("csmt") else true,
            videoPciDeviceID = if (json.has("videoPciDeviceID")) json.getInt("videoPciDeviceID") else 1728,
            offScreenRenderingMode = if (json.has("offScreenRenderingMode")) json.getString("offScreenRenderingMode") else "fbo",
            strictShaderMath = if (json.has("strictShaderMath")) json.getBoolean("strictShaderMath") else true,
            useDRI3 = if (json.has("useDRI3")) json.getBoolean("useDRI3") else true,
            videoMemorySize = if (json.has("videoMemorySize")) json.getString("videoMemorySize") else "2048",
            mouseWarpOverride = if (json.has("mouseWarpOverride")) json.getString("mouseWarpOverride") else "disable",
            sdlControllerAPI = if (json.has("sdlControllerAPI")) json.getBoolean("sdlControllerAPI") else true,
            useSteamInput = if (json.has("useSteamInput")) json.getBoolean("useSteamInput") else false,
            enableXInput = if (json.has("enableXInput")) json.getBoolean("enableXInput") else true,
            enableDInput = if (json.has("enableDInput")) json.getBoolean("enableDInput") else true,
            dinputMapperType = if (json.has("dinputMapperType")) {
                json.getInt("dinputMapperType").toByte()
            } else {
                1.toByte()
            },
            disableMouseInput = if (json.has("disableMouseInput")) json.getBoolean("disableMouseInput") else false,
            suspendPolicy = if (json.has("suspendPolicy")) {
                Container.normalizeSuspendPolicy(json.getString("suspendPolicy"))
            } else {
                PrefManager.suspendPolicy
            },
            language = if (json.has("language")) json.getString("language") else "english",
            forceDlc = if (json.has("forceDlc")) json.getBoolean("forceDlc") else false,
            localSavesOnly = if (json.has("localSavesOnly")) json.getBoolean("localSavesOnly") else false,
            steamOfflineMode = if (json.has("steamOfflineMode")) json.getBoolean("steamOfflineMode") else false,
            useLegacyDRM = if (json.has("useLegacyDRM")) json.getBoolean("useLegacyDRM") else false,
            unpackFiles = if (json.has("unpackFiles")) json.getBoolean("unpackFiles") else false,
            shaderBackend = if (json.has("shaderBackend")) json.getString("shaderBackend") else "glsl",
            useGLSL = if (json.has("useGLSL")) json.getString("useGLSL") else "enabled",
            explicitOverrideKeys = explicitKeys,
        )

        if (hgoLabPreset.isNotBlank()) {
            config = HgoLabPresets.applyPresetList(config, hgoLabPreset)
            Timber.i("[IntentLaunchManager]: Applied HGO lab preset(s) from JSON: $hgoLabPreset")
        }

        val validationIssues = validateContainerConfig(config)
        if (validationIssues.isNotEmpty()) {
            Timber.w("[IntentLaunchManager]: Container configuration validation issues: ${validationIssues.joinToString("; ")}")
        }

        return config
    }

    private fun normalizeDxWrapperConfig(rawConfig: String): String {
        val config = rawConfig.trim()
        if (config.isEmpty()) return ""

        val firstEntry = config.substringBefore(",")
        return if (firstEntry.contains("=")) config else "version=$config"
    }

    private fun ContainerData.withLaunchDefaults(): ContainerData {
        val defaults = ContainerData()
        return copy(
            screenSize = screenSize.ifBlank { defaults.screenSize },
            envVars = envVars.ifBlank { defaults.envVars },
            graphicsDriver = graphicsDriver.ifBlank { defaults.graphicsDriver },
            graphicsDriverVersion = graphicsDriverVersion.ifBlank { defaults.graphicsDriverVersion },
            graphicsDriverConfig = graphicsDriverConfig.ifBlank { Container.DEFAULT_GRAPHICSDRIVERCONFIG },
            dxwrapper = dxwrapper.ifBlank { defaults.dxwrapper },
            dxwrapperConfig = dxwrapperConfig.ifBlank { DXVKHelper.DEFAULT_CONFIG },
            audioDriver = audioDriver.ifBlank { defaults.audioDriver },
            wincomponents = wincomponents.ifBlank { defaults.wincomponents },
            drives = drives.ifBlank { defaults.drives },
            steamType = steamType.ifBlank { defaults.steamType },
            cpuList = cpuList.ifBlank { defaults.cpuList },
            cpuListWoW64 = cpuListWoW64.ifBlank { defaults.cpuListWoW64 },
            box86Version = box86Version.ifBlank { defaults.box86Version },
            box64Version = box64Version.ifBlank { defaults.box64Version },
            box86Preset = box86Preset.ifBlank { defaults.box86Preset },
            box64Preset = box64Preset.ifBlank { defaults.box64Preset },
            desktopTheme = desktopTheme.ifBlank { defaults.desktopTheme },
            containerVariant = containerVariant.ifBlank { defaults.containerVariant },
            wineVersion = wineVersion.ifBlank { defaults.wineVersion },
            emulator = emulator.ifBlank { defaults.emulator },
            fexcoreVersion = fexcoreVersion.ifBlank { defaults.fexcoreVersion },
            fexcoreTSOMode = fexcoreTSOMode.ifBlank { defaults.fexcoreTSOMode },
            fexcoreX87Mode = fexcoreX87Mode.ifBlank { defaults.fexcoreX87Mode },
            fexcoreMultiBlock = fexcoreMultiBlock.ifBlank { defaults.fexcoreMultiBlock },
            fexcorePreset = fexcorePreset.ifBlank { defaults.fexcorePreset },
            renderer = renderer.ifBlank { defaults.renderer },
            offScreenRenderingMode = offScreenRenderingMode.ifBlank { defaults.offScreenRenderingMode },
            videoMemorySize = videoMemorySize.ifBlank { defaults.videoMemorySize },
            mouseWarpOverride = mouseWarpOverride.ifBlank { defaults.mouseWarpOverride },
            suspendPolicy = suspendPolicy.ifBlank { defaults.suspendPolicy },
            language = language.ifBlank { defaults.language },
            shaderBackend = shaderBackend.ifBlank { defaults.shaderBackend },
            useGLSL = useGLSL.ifBlank { defaults.useGLSL },
        )
    }

    private fun mergeConfigurations(base: ContainerData, override: ContainerData): ContainerData {
        // Quick return if no actual overrides
        if (override == base) return base

        val hasExplicitKeys = override.explicitOverrideKeys.isNotEmpty()
        fun explicit(key: String): Boolean = override.explicitOverrideKeys.contains(key)

        return ContainerData(
            name = override.name.ifEmpty { base.name },
            screenSize = if (override.screenSize != Container.DEFAULT_SCREEN_SIZE) {
                override.screenSize
            } else {
                base.screenSize
            },
            envVars = if ((hasExplicitKeys && explicit("envVars")) || override.envVars != Container.DEFAULT_ENV_VARS) override.envVars else base.envVars,
            graphicsDriver = if ((hasExplicitKeys && explicit("graphicsDriver")) || override.graphicsDriver != Container.DEFAULT_GRAPHICS_DRIVER) {
                override.graphicsDriver
            } else {
                base.graphicsDriver
            },
            graphicsDriverVersion = override.graphicsDriverVersion.ifEmpty { base.graphicsDriverVersion },
            graphicsDriverConfig = if ((hasExplicitKeys && explicit("graphicsDriverConfig")) || override.graphicsDriverConfig.isNotEmpty()) {
                override.graphicsDriverConfig
            } else {
                base.graphicsDriverConfig
            },
            dxwrapper = if ((hasExplicitKeys && explicit("dxwrapper")) || override.dxwrapper != Container.DEFAULT_DXWRAPPER) override.dxwrapper else base.dxwrapper,
            dxwrapperConfig = when {
                (hasExplicitKeys && explicit("dxwrapperConfig")) || override.dxwrapperConfig.isNotEmpty() -> override.dxwrapperConfig
                base.dxwrapperConfig.isNotEmpty() -> base.dxwrapperConfig
                else -> DXVKHelper.DEFAULT_CONFIG
            },
            audioDriver = if ((hasExplicitKeys && explicit("audioDriver")) || override.audioDriver != Container.DEFAULT_AUDIO_DRIVER) override.audioDriver else base.audioDriver,
            wincomponents = if ((hasExplicitKeys && explicit("wincomponents")) || override.wincomponents != Container.DEFAULT_WINCOMPONENTS) {
                override.wincomponents
            } else {
                base.wincomponents
            },
            drives = if ((hasExplicitKeys && explicit("drives")) || override.drives != Container.DEFAULT_DRIVES) override.drives else base.drives,
            execArgs = if (hasExplicitKeys && explicit("execArgs")) override.execArgs else override.execArgs.ifEmpty { base.execArgs },
            executablePath = if (hasExplicitKeys && explicit("executablePath")) override.executablePath else override.executablePath.ifEmpty { base.executablePath },
            installPath = if (hasExplicitKeys && explicit("installPath")) override.installPath else override.installPath.ifEmpty { base.installPath },
            showFPS = if ((hasExplicitKeys && explicit("showFPS")) || override.showFPS != false) override.showFPS else base.showFPS,
            launchRealSteam = if ((hasExplicitKeys && explicit("launchRealSteam")) || override.launchRealSteam != false) override.launchRealSteam else base.launchRealSteam,
            allowSteamUpdates = if ((hasExplicitKeys && explicit("allowSteamUpdates")) || override.allowSteamUpdates != false) {
                override.allowSteamUpdates
            } else {
                base.allowSteamUpdates
            },
            steamType = override.steamType.ifEmpty { base.steamType },
            cpuList = if (override.cpuList != Container.getFallbackCPUList()) override.cpuList else base.cpuList,
            cpuListWoW64 = if (override.cpuListWoW64 != Container.getFallbackCPUListWoW64()) {
                override.cpuListWoW64
            } else {
                base.cpuListWoW64
            },
            wow64Mode = if ((hasExplicitKeys && explicit("wow64Mode")) || override.wow64Mode != true) override.wow64Mode else base.wow64Mode,
            startupSelection = if ((hasExplicitKeys && explicit("startupSelection")) || override.startupSelection != Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte()) {
                override.startupSelection
            } else {
                base.startupSelection
            },
            box86Version = override.box86Version.ifEmpty { base.box86Version },
            box64Version = override.box64Version.ifEmpty { base.box64Version },
            box86Preset = override.box86Preset.ifEmpty { base.box86Preset },
            box64Preset = override.box64Preset.ifEmpty { base.box64Preset },
            desktopTheme = override.desktopTheme.ifEmpty { base.desktopTheme },
            containerVariant = override.containerVariant.ifEmpty { base.containerVariant },
            wineVersion = override.wineVersion.ifEmpty { base.wineVersion },
            emulator = override.emulator.ifEmpty { base.emulator },
            fexcoreVersion = override.fexcoreVersion.ifEmpty { base.fexcoreVersion },
            fexcoreTSOMode = override.fexcoreTSOMode.ifEmpty { base.fexcoreTSOMode },
            fexcoreX87Mode = override.fexcoreX87Mode.ifEmpty { base.fexcoreX87Mode },
            fexcoreMultiBlock = override.fexcoreMultiBlock.ifEmpty { base.fexcoreMultiBlock },
            fexcorePreset = override.fexcorePreset.ifEmpty { base.fexcorePreset },
            renderer = if ((hasExplicitKeys && explicit("renderer")) || override.renderer != "gl") override.renderer else base.renderer,
            csmt = if ((hasExplicitKeys && explicit("csmt")) || override.csmt != true) override.csmt else base.csmt,
            videoPciDeviceID = if (override.videoPciDeviceID != 1728) override.videoPciDeviceID else base.videoPciDeviceID,
            offScreenRenderingMode = if ((hasExplicitKeys && explicit("offScreenRenderingMode")) || override.offScreenRenderingMode != "fbo") {
                override.offScreenRenderingMode
            } else {
                base.offScreenRenderingMode
            },
            strictShaderMath = if ((hasExplicitKeys && explicit("strictShaderMath")) || override.strictShaderMath != true) override.strictShaderMath else base.strictShaderMath,
            useDRI3 = if ((hasExplicitKeys && explicit("useDRI3")) || override.useDRI3 != true) override.useDRI3 else base.useDRI3,
            videoMemorySize = if ((hasExplicitKeys && explicit("videoMemorySize")) || override.videoMemorySize != "2048") override.videoMemorySize else base.videoMemorySize,
            mouseWarpOverride = if ((hasExplicitKeys && explicit("mouseWarpOverride")) || override.mouseWarpOverride != "disable") override.mouseWarpOverride else base.mouseWarpOverride,
            sdlControllerAPI = if ((hasExplicitKeys && explicit("sdlControllerAPI")) || override.sdlControllerAPI != true) override.sdlControllerAPI else base.sdlControllerAPI,
            useSteamInput = if ((hasExplicitKeys && explicit("useSteamInput")) || override.useSteamInput != false) override.useSteamInput else base.useSteamInput,
            enableXInput = if ((hasExplicitKeys && explicit("enableXInput")) || override.enableXInput != true) override.enableXInput else base.enableXInput,
            enableDInput = if ((hasExplicitKeys && explicit("enableDInput")) || override.enableDInput != true) override.enableDInput else base.enableDInput,
            dinputMapperType = if ((hasExplicitKeys && explicit("dinputMapperType")) || override.dinputMapperType != 1.toByte()) override.dinputMapperType else base.dinputMapperType,
            disableMouseInput = if ((hasExplicitKeys && explicit("disableMouseInput")) || override.disableMouseInput != false) override.disableMouseInput else base.disableMouseInput,
            suspendPolicy = override.suspendPolicy.ifEmpty { base.suspendPolicy },
            language = override.language.ifEmpty { base.language },
            forceDlc = if (override.forceDlc != false) override.forceDlc else base.forceDlc,
            localSavesOnly = if (override.localSavesOnly != false) override.localSavesOnly else base.localSavesOnly,
            steamOfflineMode = if (override.steamOfflineMode != false) override.steamOfflineMode else base.steamOfflineMode,
            useLegacyDRM = if (override.useLegacyDRM != false) override.useLegacyDRM else base.useLegacyDRM,
            unpackFiles = if (override.unpackFiles != false) override.unpackFiles else base.unpackFiles,
            shaderBackend = if (override.shaderBackend != "glsl") override.shaderBackend else base.shaderBackend,
            useGLSL = if (override.useGLSL != "enabled") override.useGLSL else base.useGLSL,
        )
    }
}

private object TemporaryConfigStore {
    private val overrides = mutableMapOf<String, ContainerData>()
    private val originalConfigs = mutableMapOf<String, ContainerData>()
    private val lock = Any()

    fun setOverride(appId: String, config: ContainerData) = synchronized(lock) {
        overrides[appId] = config
    }

    fun getOverride(appId: String): ContainerData? = synchronized(lock) {
        overrides[appId]
    }

    fun clearOverride(appId: String) = synchronized(lock) {
        overrides.remove(appId)
        originalConfigs.remove(appId)
    }

    fun hasOverride(appId: String): Boolean = synchronized(lock) {
        overrides.containsKey(appId)
    }

    fun setOriginalConfig(appId: String, config: ContainerData) = synchronized(lock) {
        originalConfigs[appId] = config
    }

    fun getOriginalConfig(appId: String): ContainerData? = synchronized(lock) {
        originalConfigs[appId]
    }

    fun clearAll() = synchronized(lock) {
        overrides.clear()
        originalConfigs.clear()
    }
}
