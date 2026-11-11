package org.futo.inputmethod.latin.uix.settings.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.EXPERIMENTAL_OPENCL
import org.futo.inputmethod.latin.uix.EXPERIMENTAL_OPENCL_FAST
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.SYSTEM_VOICE_INPUT_PACKAGE
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.settings.CollapsibleSection
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.SettingsSection
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.voiceinput.shared.opencl.OpenClCapabilities
import org.futo.voiceinput.shared.opencl.OpenClCapabilityGate
import org.futo.voiceinput.shared.opencl.OpenClDiagnostics
import org.futo.voiceinput.shared.opencl.OpenClResources
import org.futo.voiceinput.shared.opencl.OpenClSupport
private val visibilityCheckNotSystemVoiceInput = @Composable {
    useDataStoreValue(USE_SYSTEM_VOICE_INPUT) == false
}

/**
 * v44: the settings page starts one bounded, isolated capability probe. While
 * it is unknown (including malformed/timeout/death), no OpenCL toggle is
 * rendered; only a supported result reveals the controls. The process cache
 * means each item's LaunchedEffect reuses the same result instead of binding
 * again per recomposition.
 */
@Composable
private fun rememberOpenClCapabilities(): OpenClCapabilities? {
    val context = LocalContext.current
    // v45: every row observes the one process result, so the bounded retry
    // updates visibility and the status line together instead of leaving
    // stale hidden controls behind.
    val shared by OpenClCapabilityGate.updates.collectAsState()
    LaunchedEffect(context) {
        OpenClCapabilityGate.ensure(context)
    }
    return shared ?: OpenClCapabilityGate.peek()
}

private val visibilityCheckOpenCl = @Composable {
    visibilityCheckWhisper() &&
        rememberOpenClCapabilities()?.controlsVisible == true
}

private val visibilityCheckFastDictation = @Composable {
    visibilityCheckWhisper() &&
        useDataStoreValue(EXPERIMENTAL_OPENCL) == true &&
        rememberOpenClCapabilities()?.controlsVisible == true
}

/** v45: content-free status when the capability controls are hidden. */
private val visibilityCheckOpenClStatus = @Composable {
    visibilityCheckWhisper() &&
        rememberOpenClCapabilities()?.controlsVisible != true
}

private data class VoiceIMEInfo(
    val builtin: Boolean,
    val name: String,
    val packageName: String,
)

@Composable
fun usePackageReadableName(pkg: String): String? {
    val context = LocalContext.current
    return remember(pkg) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
        } catch(e: Exception) {
            null
        }?.applicationInfo?.let {
            context.packageManager.getApplicationLabel(it).toString()
        }
    }
}

@Composable
private fun OpenClDetails(capabilities: OpenClCapabilities?, includeTimings: Boolean) {
    val context = LocalContext.current
    val technicalStatus = capabilities?.diagnostic()
        ?: stringResource(R.string.voice_input_opencl_status_checking)
    CollapsibleSection(stringResource(R.string.voice_settings_diagnostics)) {
        Text(
            stringResource(R.string.voice_settings_opencl_details),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
        SelectionContainer {
            Text(technicalStatus, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
        SettingItem(title = stringResource(R.string.voice_settings_copy_capability), onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("OpenCL capability", technicalStatus))
        }) {}
        if (org.futo.inputmethod.latin.BuildConfig.DEBUG) {
            SettingItem(title = "Whistle lifecycle validation (debug)", onClick = {
                context.startActivity(android.content.Intent().setClassName(context,
                    "org.futo.inputmethod.latin.whistle.WhistleDiagnosticActivity"))
            }) {}
        }
        if (includeTimings) {
            SettingItem(title = stringResource(R.string.voice_input_opencl_timings), onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("Voice timing diagnostics", OpenClDiagnostics.export(context)))
            }) {}
        }
    }
}

@Composable
private fun ParakeetSettings() {
    val context = LocalContext.current
    val store = remember { org.futo.voiceinput.shared.provisioning.ParakeetStore(context.applicationContext.filesDir) }
    val engine = useDataStore(org.futo.inputmethod.latin.uix.VOICE_INPUT_ENGINE)
    val state by ParakeetImportUi.state.collectAsState()
    val ready = remember(state) { store.isReady() }
    val selected = org.futo.voiceinput.shared.RecognitionEngine.fromPreference(engine.value,
        android.os.Build.VERSION.SDK_INT >= 27 && ready)
    val status = stringResource(if (ready) R.string.parakeet_ready else R.string.parakeet_not_ready)
    val options = if (android.os.Build.VERSION.SDK_INT >= 27) listOf("whisper", "parakeet") else listOf("whisper")
    DropDownPickerSettingItem(stringResource(R.string.voice_engine), options, selected.preferenceValue,
        { engine.setValue(it) }, { if (it == "parakeet") "Parakeet · $status" else "Whisper" })
    // Keep the missing-model prompt from the default-engine flow, and allow
    // managing an imported model even when Whisper is explicitly selected.
    val nav = org.futo.inputmethod.latin.uix.LocalNavController.current
    org.futo.inputmethod.latin.uix.settings.NavigationItem(
        title = stringResource(if (ready) R.string.parakeet_model else R.string.parakeet_download),
        subtitle = status,
        style = NavigationItemStyle.Misc,
        navigate = { nav?.navigate("parakeetModel") }
    )
}

private val visibilityCheckWhisper = @Composable {
    val context = LocalContext.current
    val store = remember { org.futo.voiceinput.shared.provisioning.ParakeetStore(context.applicationContext.filesDir) }
    val state by ParakeetImportUi.state.collectAsState()
    val ready = remember(state) { store.isReady() }
    val engine = org.futo.voiceinput.shared.RecognitionEngine.fromPreference(
        useDataStoreValue(org.futo.inputmethod.latin.uix.VOICE_INPUT_ENGINE),
        android.os.Build.VERSION.SDK_INT >= 27 && ready)
    visibilityCheckNotSystemVoiceInput() && engine == org.futo.voiceinput.shared.RecognitionEngine.Whisper
}

val VoiceInputMenu = UserSettingsMenu(
    title = R.string.voice_input_settings_title,
    navPath = "voiceInput", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly { SettingsSection(stringResource(R.string.voice_settings_section_provider)) },

        UserSetting(
            name = R.string.voice_input_settings_backend_system,
            searchTagList = listOf(
                R.string.voice_input_settings_disable_builtin_voice_input,
                R.string.voice_input_settings_disable_builtin_voice_input_subtitle
            )
        ) {
            val useExternal = useDataStore(USE_SYSTEM_VOICE_INPUT)
            val externalPkg = useDataStore(SYSTEM_VOICE_INPUT_PACKAGE)

            val context = LocalContext.current
            val res = LocalResources.current
            val options = remember(externalPkg.value) {
                val imm = context.getSystemService<InputMethodManager>()!!
                buildList {
                    add(VoiceIMEInfo(true, "", ""))
                    addAll(imm.enabledInputMethodList.filter { im ->
                        im.packageName == externalPkg.value ||
                            (0 until im.subtypeCount).map { im.getSubtypeAt(it) }
                                .any { it.mode.lowercase() == "voice" }
                    }.map {
                        VoiceIMEInfo(false, it.loadLabel(context.packageManager)?.toString() ?: it.packageName, it.packageName)
                    })
                }
            }

            val currOption = remember(externalPkg.value) {
                if(externalPkg.value == "") options[0] else
                options.find { it.packageName == externalPkg.value }
            }

            DropDownPickerSettingItem(
                stringResource(R.string.voice_input_settings_backend_system),
                options,
                currOption,
                {
                    useExternal.setValue(!it.builtin)
                    externalPkg.setValue(it.packageName ?: "")
                },
                {
                    if(it.builtin) res.getString(R.string.voice_input_settings_backend_system_internal)
                    else it.name
                }
            )

            if(useExternal.value && externalPkg.value.isNotEmpty()) {
                val privacyWhitelist = listOf(
                    "org.futo.voiceinput",
                    "org.futo.voiceinput.dev",
                    "dev.notune.transcribe",
                    "dev.soupslurpr.transcribro"
                )

                if(!privacyWhitelist.contains(externalPkg.value))
                    Tip(stringResource(R.string.voice_input_settings_backend_system_external_warning,
                        currOption?.name ?: externalPkg.value))
            }
        },

        userSettingDecorationOnly { SettingsSection(stringResource(R.string.voice_settings_section_audio)) }.copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_bluetooth_mic,
            subtitle = R.string.voice_input_settings_use_bluetooth_mic_subtitle,
            setting = PREFER_BLUETOOTH
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_audio_focus,
            subtitle = R.string.voice_input_settings_audio_focus_subtitle,
            setting = AUDIO_FOCUS
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_indication_sounds,
            subtitle = R.string.voice_input_settings_indication_sounds_subtitle,
            setting = ENABLE_SOUND
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingDecorationOnly { SettingsSection(stringResource(R.string.voice_settings_section_recognition)) }.copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingDecorationOnly { ParakeetSettings() }.copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_personal_dict,
            subtitle = R.string.parakeet_personal_dictionary_subtitle,
            setting = USE_PERSONAL_DICT
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_suppress_symbols,
            setting = DISALLOW_SYMBOLS
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_long_form,
            subtitle = R.string.voice_input_settings_long_form_subtitle,
            setting = CAN_EXPAND_SPACE
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_autostop_vad,
            subtitle = R.string.voice_input_settings_autostop_vad_subtitle,
            setting = USE_VAD_AUTOSTOP
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_animate_bubble,
            subtitle = R.string.voice_input_settings_animate_bubble_subtitle,
            setting = ANIMATE_BUBBLE
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingNavigationItem(
            title = R.string.voice_input_settings_change_models,
            subtitle = R.string.voice_input_settings_change_models_subtitle,
            style = NavigationItemStyle.Misc,
            navigateTo = "languages"
        ).copy(visibilityCheck = visibilityCheckWhisper),

        userSettingDecorationOnly { SettingsSection(stringResource(R.string.voice_settings_section_acceleration)) }.copy(visibilityCheck = visibilityCheckWhisper),

        userSettingToggleDataStore(
            title = R.string.voice_input_experimental_opencl,
            subtitle = R.string.voice_input_experimental_opencl_summary,
            setting = EXPERIMENTAL_OPENCL
        ).copy(visibilityCheck = visibilityCheckOpenCl),

        userSettingToggleDataStore(
            title = R.string.voice_input_experimental_opencl_fast,
            subtitle = R.string.voice_input_experimental_opencl_fast_summary,
            setting = EXPERIMENTAL_OPENCL_FAST
        ).copy(visibilityCheck = visibilityCheckFastDictation),

        UserSetting(name = R.string.voice_input_opencl_timings) {
            val enabled = useDataStoreValue(EXPERIMENTAL_OPENCL)
            LaunchedEffect(enabled) { if (!enabled) OpenClResources.releaseIdle() }
            // v21: turning Fast off retires fast-mode residency only (next
            // fast request cold-verifies); normal-GPU idle bindings and any
            // in-flight request are untouched.
            val fast = useDataStoreValue(EXPERIMENTAL_OPENCL_FAST)
            LaunchedEffect(fast) { if (!fast) OpenClResources.releaseFast() }
            OpenClDetails(rememberOpenClCapabilities(), includeTimings = true)
        }.copy(visibilityCheck = visibilityCheckOpenCl),

        // Keep the original visibility and bounded retry; technical status is
        // available under diagnostics without crowding the main settings list.
        UserSetting(name = R.string.voice_input_opencl_status, appearsInSearch = false) {
            val context = LocalContext.current
            val capabilities = rememberOpenClCapabilities()
            val scope = rememberCoroutineScope()
            Column {
                val statusText = when (capabilities?.support) {
                    null -> R.string.voice_settings_gpu_checking
                    OpenClSupport.SUPPORTED -> R.string.voice_settings_gpu_supported
                    OpenClSupport.UNSUPPORTED -> R.string.voice_settings_gpu_unsupported
                    OpenClSupport.UNAVAILABLE -> R.string.voice_settings_gpu_unavailable
                    OpenClSupport.UNKNOWN -> R.string.voice_settings_gpu_unknown
                }
                SettingItem(
                    title = stringResource(R.string.voice_input_experimental_opencl),
                    subtitle = stringResource(statusText)
                ) {}
                if (capabilities?.retryable == true) {
                    SettingItem(title = stringResource(R.string.voice_input_opencl_retry), onClick = {
                        scope.launch { OpenClCapabilityGate.retryTransient(context) }
                    }) {}
                }
                OpenClDetails(capabilities, includeTimings = false)
            }
        }.copy(visibilityCheck = visibilityCheckOpenClStatus),

        // The Whisper acceleration rows above carry diagnostics; keep the timing
        // report reachable while Parakeet is the effective engine.
        UserSetting(name = R.string.voice_input_opencl_timings, appearsInSearch = false) {
            val context = LocalContext.current
            CollapsibleSection(stringResource(R.string.voice_settings_diagnostics)) {
                SettingItem(title = stringResource(R.string.voice_input_opencl_timings), onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("Voice timing diagnostics", OpenClDiagnostics.export(context)))
                }) {}
            }
        }.copy(visibilityCheck = { visibilityCheckNotSystemVoiceInput() && !visibilityCheckWhisper() }),

    )
)
