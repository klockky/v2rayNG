package com.v2ray.ang.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.devicekit.R as DkR
import com.v2ray.devicekit.UserAgentPreset
import java.util.Locale
import kotlin.random.Random

/**
 * Compose port of the DeviceKit add-on preferences (HWID / custom User-Agent headers).
 * Keys must match com.v2ray.devicekit.PrefKeys: the add-on reads them from the same MMKV storage.
 */
@Composable
fun DeviceKitSettingsGroup() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    var enabled by rememberMmkvBool("pref_hwid_enabled", false)
    var hwid by rememberMmkvString("pref_hwid_val", "")
    var os by rememberMmkvString("pref_hwid_os", "android")
    var osVer by rememberMmkvString("pref_hwid_os_ver", "")
    var model by rememberMmkvString("pref_hwid_model", "")
    var locale by rememberMmkvString("pref_hwid_locale", "")
    var uaPreset by rememberMmkvString("pref_hwid_user_agent_preset", "auto")
    var uaCustom by rememberMmkvString("pref_hwid_user_agent", "")
    var happVersion by rememberMmkvString("pref_hwid_user_agent_happ_version", "")
    var v2rayngVersion by rememberMmkvString("pref_hwid_user_agent_v2rayng_version", "")
    var v2raytunPlatform by rememberMmkvString("pref_hwid_v2raytun_platform", "android")
    var flclashxVersion by rememberMmkvString("pref_hwid_user_agent_flclashx_version", "")
    var flclashxPlatform by rememberMmkvString("pref_hwid_flclashx_platform", "android")

    // Fill blank fields with the real device values once the override is enabled
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        if (hwid.isBlank()) hwid = androidId(context)
        if (osVer.isBlank()) osVer = Build.VERSION.RELEASE.orEmpty()
        if (model.isBlank()) model = Build.MODEL?.ifEmpty { null } ?: "Unknown"
        if (locale.isBlank()) locale = Locale.getDefault().language.orEmpty()
        if (happVersion.isBlank()) happVersion = "3.14.0"
        if (v2rayngVersion.isBlank()) v2rayngVersion = BuildConfig.VERSION_NAME
        if (flclashxVersion.isBlank()) flclashxVersion = "0.3.0"
    }

    val preset = UserAgentPreset.fromKey(uaPreset)

    CollapsiblePreferenceGroupHeader(
        title = stringResource(DkR.string.title_devicekit_settings),
        expanded = expanded,
        onExpandedChange = { expanded = it }
    )
    if (!expanded) return

    SettingsSwitchItem(
        title = stringResource(DkR.string.title_pref_hwid_enabled),
        summary = stringResource(DkR.string.summary_pref_hwid_enabled),
        checked = enabled,
        onCheckedChange = { enabled = it }
    )
    if (!enabled) return

    SettingsEditItem(
        title = stringResource(DkR.string.title_pref_hwid_val),
        value = hwid,
        onValueChanged = { hwid = it }
    )
    SettingsMenuItem(
        title = stringResource(DkR.string.title_pref_hwid_randomize),
        subtitle = stringResource(DkR.string.summary_pref_hwid_randomize),
        onClick = {
            hwid = ByteArray(8).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        }
    )
    SettingsListItem(
        title = stringResource(DkR.string.title_pref_hwid_os),
        entries = stringArrayResource(DkR.array.hwid_os_entries).toList(),
        values = stringArrayResource(DkR.array.hwid_os_values).toList(),
        selectedValue = os,
        onSelected = { os = it }
    )
    SettingsEditItem(
        title = stringResource(DkR.string.title_pref_hwid_os_ver),
        value = osVer,
        onValueChanged = { osVer = it }
    )
    SettingsEditItem(
        title = stringResource(DkR.string.title_pref_hwid_model),
        value = model,
        onValueChanged = { model = it }
    )
    SettingsEditItem(
        title = stringResource(DkR.string.title_pref_hwid_locale),
        value = locale,
        onValueChanged = { locale = it }
    )
    SettingsListItem(
        title = stringResource(DkR.string.title_pref_hwid_user_agent_preset),
        entries = stringArrayResource(DkR.array.hwid_user_agent_preset_entries).toList(),
        values = stringArrayResource(DkR.array.hwid_user_agent_preset_values).toList(),
        selectedValue = uaPreset,
        onSelected = { uaPreset = it }
    )
    when (preset) {
        UserAgentPreset.HAPP -> SettingsEditItem(
            title = stringResource(DkR.string.title_pref_hwid_user_agent_happ_version),
            value = happVersion,
            onValueChanged = { happVersion = it }
        )

        UserAgentPreset.V2RAYNG -> SettingsEditItem(
            title = stringResource(DkR.string.title_pref_hwid_user_agent_v2rayng_version),
            value = v2rayngVersion,
            onValueChanged = { v2rayngVersion = it }
        )

        UserAgentPreset.V2RAYTUN -> SettingsListItem(
            title = stringResource(DkR.string.title_pref_hwid_v2raytun_platform),
            entries = stringArrayResource(DkR.array.hwid_v2raytun_platform_entries).toList(),
            values = stringArrayResource(DkR.array.hwid_v2raytun_platform_values).toList(),
            selectedValue = v2raytunPlatform,
            onSelected = { v2raytunPlatform = it }
        )

        UserAgentPreset.FLCLASHX -> {
            SettingsEditItem(
                title = stringResource(DkR.string.title_pref_hwid_user_agent_flclashx_version),
                value = flclashxVersion,
                onValueChanged = { flclashxVersion = it }
            )
            SettingsListItem(
                title = stringResource(DkR.string.title_pref_hwid_flclashx_platform),
                entries = stringArrayResource(DkR.array.hwid_flclashx_platform_entries).toList(),
                values = stringArrayResource(DkR.array.hwid_flclashx_platform_values).toList(),
                selectedValue = flclashxPlatform,
                onSelected = { flclashxPlatform = it }
            )
        }

        UserAgentPreset.CUSTOM -> SettingsEditItem(
            title = stringResource(DkR.string.title_pref_hwid_user_agent),
            value = uaCustom,
            onValueChanged = { uaCustom = it }
        )

        UserAgentPreset.AUTO -> Unit
    }
}

@SuppressLint("HardwareIds")
private fun androidId(context: Context): String =
    runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
        .getOrNull().orEmpty()
