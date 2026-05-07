package com.v2ray.ang.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AngApplication
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import okhttp3.Request
import java.util.Locale
import kotlin.random.Random

/**
 * Sends device-identification headers (X-HWID, X-Device-OS, X-Ver-OS,
 * X-Device-Locale, X-Device-Model) and a custom User-Agent on subscription
 * fetches, mimicking the request signature of clients like Happ / v2raytun /
 * FlClash X / v2rayNG. Required by some commercial subscription providers
 * that bind a profile to a single device by HWID.
 *
 * In-tree port of klockky/v2rayNG-DeviceKit-Addon, adapted to upstream's
 * OkHttp HttpUtil (was HttpURLConnection-based). MMKV id is "SETTING" — the
 * same store used by [MmkvPreferenceDataStore], so the user can edit values
 * directly from the standard Settings screen.
 */
object DeviceKit {

    object Prefs {
        const val ENABLED = "pref_hwid_enabled"
        const val HWID = "pref_hwid_val"
        const val OS = "pref_hwid_os"
        const val OS_VER = "pref_hwid_os_ver"
        const val MODEL = "pref_hwid_model"
        const val LOCALE = "pref_hwid_locale"
        const val RANDOMIZE = "pref_hwid_randomize"
        const val UA_PRESET = "pref_hwid_user_agent_preset"
        const val UA_CUSTOM = "pref_hwid_user_agent"
        const val UA_HAPP_VERSION = "pref_hwid_user_agent_happ_version"
        const val UA_V2RAYNG_VERSION = "pref_hwid_user_agent_v2rayng_version"
        const val UA_V2RAYTUN_PLATFORM = "pref_hwid_v2raytun_platform"
        const val UA_FLCLASHX_VERSION = "pref_hwid_user_agent_flclashx_version"
        const val UA_FLCLASHX_PLATFORM = "pref_hwid_flclashx_platform"
    }

    private object Defaults {
        const val OS_ANDROID = "android"
        const val HAPP_VERSION = "3.14.0"
        const val V2RAYTUN_PLATFORM = "android"
        const val FLCLASHX_VERSION = "0.3.0"
        const val FLCLASHX_PLATFORM = "android"
    }

    enum class UaPreset(val key: String) {
        AUTO("auto"),
        HAPP("happ"),
        V2RAYNG("v2rayng"),
        V2RAYTUN("v2raytun"),
        FLCLASHX("flclashx"),
        CUSTOM("custom");

        companion object {
            fun fromKey(value: String?): UaPreset =
                entries.firstOrNull {
                    it.key == value?.trim()?.lowercase(Locale.US).orEmpty()
                } ?: AUTO
        }
    }

    data class Config(
        val enabled: Boolean,
        val customHwid: String? = null,
        val customOs: String? = null,
        val customOsVersion: String? = null,
        val customLocale: String? = null,
        val customModel: String? = null,
        val uaPreset: UaPreset = UaPreset.AUTO,
        val customUserAgent: String? = null,
        val happVersion: String? = null,
        val v2rayngVersion: String? = null,
        val v2raytunPlatform: String? = null,
        val flclashxVersion: String? = null,
        val flclashxPlatform: String? = null,
    )

    // -- entry points --------------------------------------------------------

    /**
     * Resolves the User-Agent header for a subscription request, taking into
     * account: (1) per-subscription override, (2) DeviceKit preset, (3) default.
     * Always returns a non-empty value.
     */
    fun resolveUserAgent(subscriptionUserAgent: String?, defaultUserAgent: String): String {
        return resolveUserAgent(loadConfig(), subscriptionUserAgent, defaultUserAgent)
    }

    /**
     * Adds DeviceKit headers (User-Agent + HWID set) to an OkHttp request
     * builder. Safe to call when DeviceKit is disabled — only the User-Agent
     * is set in that case (subscription override, otherwise default).
     */
    fun applyTo(
        builder: Request.Builder,
        subscriptionUserAgent: String?,
        defaultUserAgent: String,
    ) {
        val config = loadConfig()
        builder.header("User-agent", resolveUserAgent(config, subscriptionUserAgent, defaultUserAgent))
        if (!config.enabled) return

        val context = AngApplication.application
        val hwid = config.customHwid?.trim().orEmpty().ifEmpty { DeviceInfo.hardwareId(context) }
        if (hwid.isEmpty()) return
        builder.header("X-HWID", hwid)

        val osRaw = config.customOs?.trim().orEmpty().ifEmpty { Defaults.OS_ANDROID }
        builder.header("X-Device-OS", osHeaderValue(osRaw))

        val osVer = config.customOsVersion?.trim().orEmpty().ifEmpty { DeviceInfo.osVersion() }
        builder.header("X-Ver-OS", osVer)

        val locale = config.customLocale?.trim().orEmpty().ifEmpty { DeviceInfo.locale() }
        if (locale.isNotEmpty()) builder.header("X-Device-Locale", locale)

        val model = config.customModel?.trim().orEmpty().ifEmpty { DeviceInfo.model() }
        builder.header("X-Device-Model", model)
    }

    /**
     * Inflates the DeviceKit preference category into the given Settings
     * fragment and wires up dynamic preset/visibility logic.
     */
    fun installUi(fragment: PreferenceFragmentCompat) {
        val screen = fragment.preferenceScreen
            ?: fragment.preferenceManager.createPreferenceScreen(fragment.requireContext()).also {
                fragment.preferenceScreen = it
            }
        val ok = runCatching {
            fragment.preferenceManager.inflateFromResource(
                fragment.requireContext(), R.xml.pref_devicekit, screen
            )
        }.isSuccess
        if (!ok) runCatching { fragment.addPreferencesFromResource(R.xml.pref_devicekit) }

        installSummaryProviders(fragment)
        bindBehaviour(fragment)
    }

    // -- config loading ------------------------------------------------------

    private fun loadConfig(): Config {
        return try {
            val s = MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE)
            val enabled = s.decodeBool(Prefs.ENABLED, false)
            val preset = if (enabled) UaPreset.fromKey(s.decodeString(Prefs.UA_PRESET)) else UaPreset.AUTO
            Config(
                enabled = enabled,
                customHwid = s.decodeString(Prefs.HWID),
                customOs = s.decodeString(Prefs.OS),
                customOsVersion = s.decodeString(Prefs.OS_VER),
                customLocale = s.decodeString(Prefs.LOCALE),
                customModel = s.decodeString(Prefs.MODEL),
                uaPreset = preset,
                customUserAgent = s.decodeString(Prefs.UA_CUSTOM),
                happVersion = s.decodeString(Prefs.UA_HAPP_VERSION, Defaults.HAPP_VERSION),
                v2rayngVersion = s.decodeString(Prefs.UA_V2RAYNG_VERSION, BuildConfig.VERSION_NAME),
                v2raytunPlatform = s.decodeString(Prefs.UA_V2RAYTUN_PLATFORM, Defaults.V2RAYTUN_PLATFORM),
                flclashxVersion = s.decodeString(Prefs.UA_FLCLASHX_VERSION, Defaults.FLCLASHX_VERSION),
                flclashxPlatform = s.decodeString(Prefs.UA_FLCLASHX_PLATFORM, Defaults.FLCLASHX_PLATFORM),
            )
        } catch (_: Throwable) {
            Config(enabled = false)
        }
    }

    private fun resolveUserAgent(
        config: Config,
        subscriptionUserAgent: String?,
        defaultUserAgent: String,
    ): String {
        if (!subscriptionUserAgent.isNullOrBlank()) return subscriptionUserAgent
        if (!config.enabled) return defaultUserAgent

        val ua = when (config.uaPreset) {
            UaPreset.HAPP -> {
                val v = config.happVersion?.trim().orEmpty().ifEmpty { Defaults.HAPP_VERSION }
                "Happ/$v"
            }
            UaPreset.V2RAYNG -> {
                val v = config.v2rayngVersion?.trim().orEmpty()
                if (v.isEmpty()) "v2rayNG" else "v2rayNG/$v"
            }
            UaPreset.V2RAYTUN -> {
                val p = config.v2raytunPlatform?.trim().orEmpty().ifEmpty { Defaults.V2RAYTUN_PLATFORM }
                "v2raytun/$p"
            }
            UaPreset.FLCLASHX -> {
                val p = config.flclashxPlatform?.trim().orEmpty().ifEmpty { Defaults.FLCLASHX_PLATFORM }
                val v = config.flclashxVersion?.trim().orEmpty()
                if (v.isEmpty()) "FlClash X Platform/$p" else "FlClash X/v$v Platform/$p"
            }
            UaPreset.CUSTOM -> config.customUserAgent?.takeIf { it.isNotBlank() }
            UaPreset.AUTO -> null
        }
        return ua ?: defaultUserAgent
    }

    private fun osHeaderValue(os: String): String {
        return when (os.lowercase(Locale.US)) {
            "android" -> "Android"
            "ios" -> "iOS"
            "windows" -> "Windows"
            "macos" -> "macOS"
            "linux" -> "Linux"
            else -> os
        }
    }

    // -- UI binder -----------------------------------------------------------

    private data class UiPrefs(
        val enabled: CheckBoxPreference?,
        val hwid: EditTextPreference?,
        val randomize: Preference?,
        val os: ListPreference?,
        val osVer: EditTextPreference?,
        val model: EditTextPreference?,
        val locale: EditTextPreference?,
        val uaPreset: ListPreference?,
        val uaCustom: EditTextPreference?,
        val uaHappVersion: EditTextPreference?,
        val uaV2rayngVersion: EditTextPreference?,
        val uaV2raytunPlatform: ListPreference?,
        val uaFlclashxVersion: EditTextPreference?,
        val uaFlclashxPlatform: ListPreference?,
    )

    private fun installSummaryProviders(fragment: PreferenceFragmentCompat) {
        listOf(Prefs.OS, Prefs.UA_PRESET, Prefs.UA_V2RAYTUN_PLATFORM, Prefs.UA_FLCLASHX_PLATFORM).forEach { key ->
            fragment.findPreference<ListPreference>(key)?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { it.entry ?: "" }
        }
        listOf(
            Prefs.HWID, Prefs.OS_VER, Prefs.MODEL, Prefs.LOCALE,
            Prefs.UA_HAPP_VERSION, Prefs.UA_V2RAYNG_VERSION, Prefs.UA_FLCLASHX_VERSION, Prefs.UA_CUSTOM,
        ).forEach { key ->
            fragment.findPreference<EditTextPreference>(key)?.summaryProvider =
                Preference.SummaryProvider<EditTextPreference> { it.text.orEmpty() }
        }
    }

    private fun bindBehaviour(fragment: PreferenceFragmentCompat) {
        val prefs = collectPrefs(fragment)
        val ctx = fragment.requireContext()

        prefs.enabled?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            updateVisibility(prefs, enabled, prefs.uaPreset?.value)
            if (enabled) applyDefaults(ctx, prefs)
            true
        }

        prefs.randomize?.setOnPreferenceClickListener {
            val newHwid = ByteArray(8).also { Random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            prefs.hwid?.text = newHwid
            if (prefs.hwid?.summaryProvider == null) prefs.hwid?.summary = newHwid
            true
        }

        prefs.uaPreset?.setOnPreferenceChangeListener { pref, newValue ->
            val lp = pref as ListPreference
            val v = newValue?.toString().orEmpty()
            updateUaPresetSummary(lp, v)
            updateVisibility(prefs, prefs.enabled?.isChecked == true, v)
            if (prefs.enabled?.isChecked == true) applyDefaults(ctx, prefs)
            true
        }

        updateUaPresetSummary(prefs.uaPreset, prefs.uaPreset?.value)
        updateVisibility(prefs, prefs.enabled?.isChecked == true, prefs.uaPreset?.value)
        if (prefs.enabled?.isChecked == true) applyDefaults(ctx, prefs)
    }

    private fun collectPrefs(fragment: PreferenceFragmentCompat) = UiPrefs(
        enabled = fragment.findPreference(Prefs.ENABLED),
        hwid = fragment.findPreference(Prefs.HWID),
        randomize = fragment.findPreference(Prefs.RANDOMIZE),
        os = fragment.findPreference(Prefs.OS),
        osVer = fragment.findPreference(Prefs.OS_VER),
        model = fragment.findPreference(Prefs.MODEL),
        locale = fragment.findPreference(Prefs.LOCALE),
        uaPreset = fragment.findPreference(Prefs.UA_PRESET),
        uaCustom = fragment.findPreference(Prefs.UA_CUSTOM),
        uaHappVersion = fragment.findPreference(Prefs.UA_HAPP_VERSION),
        uaV2rayngVersion = fragment.findPreference(Prefs.UA_V2RAYNG_VERSION),
        uaV2raytunPlatform = fragment.findPreference(Prefs.UA_V2RAYTUN_PLATFORM),
        uaFlclashxVersion = fragment.findPreference(Prefs.UA_FLCLASHX_VERSION),
        uaFlclashxPlatform = fragment.findPreference(Prefs.UA_FLCLASHX_PLATFORM),
    )

    private fun updateUaPresetSummary(pref: ListPreference?, value: String?) {
        if (pref == null) return
        if (pref.summaryProvider != null) return
        val v = value.orEmpty()
        val idx = pref.findIndexOfValue(v)
        pref.summary = if (idx >= 0) pref.entries[idx] else v
    }

    private fun updateVisibility(prefs: UiPrefs, enabled: Boolean, presetKey: String?) {
        prefs.hwid?.isVisible = enabled
        prefs.randomize?.isVisible = enabled
        prefs.os?.isVisible = enabled
        prefs.osVer?.isVisible = enabled
        prefs.model?.isVisible = enabled
        prefs.locale?.isVisible = enabled
        prefs.uaPreset?.isVisible = enabled

        val preset = UaPreset.fromKey(presetKey)
        prefs.uaHappVersion?.isVisible = enabled && preset == UaPreset.HAPP
        prefs.uaV2rayngVersion?.isVisible = enabled && preset == UaPreset.V2RAYNG
        prefs.uaV2raytunPlatform?.isVisible = enabled && preset == UaPreset.V2RAYTUN
        prefs.uaFlclashxPlatform?.isVisible = enabled && preset == UaPreset.FLCLASHX
        prefs.uaFlclashxVersion?.isVisible = enabled && preset == UaPreset.FLCLASHX
        prefs.uaCustom?.isVisible = enabled && preset == UaPreset.CUSTOM
    }

    private fun applyDefaults(ctx: Context, prefs: UiPrefs) {
        setTextIfBlank(prefs.hwid, DeviceInfo.hardwareId(ctx))
        setListIfBlank(prefs.os, Defaults.OS_ANDROID)
        setTextIfBlank(prefs.osVer, DeviceInfo.osVersion())
        setTextIfBlank(prefs.model, DeviceInfo.model())
        setTextIfBlank(prefs.locale, DeviceInfo.locale())
        setTextIfBlank(prefs.uaHappVersion, Defaults.HAPP_VERSION)
        setTextIfBlank(prefs.uaV2rayngVersion, BuildConfig.VERSION_NAME)
        setListIfBlank(prefs.uaV2raytunPlatform, Defaults.V2RAYTUN_PLATFORM)
        setTextIfBlank(prefs.uaFlclashxVersion, Defaults.FLCLASHX_VERSION)
        setListIfBlank(prefs.uaFlclashxPlatform, Defaults.FLCLASHX_PLATFORM)
    }

    private fun setTextIfBlank(pref: EditTextPreference?, value: String) {
        if (pref == null || value.isBlank()) return
        if (pref.text.isNullOrBlank()) {
            pref.text = value
            if (pref.summaryProvider == null) pref.summary = value
        }
    }

    private fun setListIfBlank(pref: ListPreference?, value: String) {
        if (pref == null || value.isBlank()) return
        if (pref.value.isNullOrBlank()) pref.value = value
        if (pref.summaryProvider == null) {
            val idx = pref.findIndexOfValue(pref.value)
            pref.summary = if (idx >= 0) pref.entries[idx] else pref.value
        }
    }

    // -- device probes -------------------------------------------------------

    private object DeviceInfo {
        fun hardwareId(ctx: Context): String = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Throwable) {
            ""
        }

        fun osVersion(): String = Build.VERSION.RELEASE.orEmpty()

        fun model(): String = try {
            Build.MODEL?.ifEmpty { "Unknown" } ?: "Unknown"
        } catch (_: Throwable) {
            "Unknown"
        }

        fun locale(): String = Locale.getDefault().language.orEmpty()
    }
}
