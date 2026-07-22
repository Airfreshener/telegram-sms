package com.airfreshener.telegram_sms.common.data

import android.content.SharedPreferences
import com.airfreshener.telegram_sms.model.Settings
import androidx.core.content.edit

class SharedPrefsRepository(
    private val sharedPreferences: SharedPreferences
) : PrefsRepository {

    companion object {
        private const val DNS_SWITCH_KEY = "doh_switch"
        private const val PRIVACY_MODE_KEY = "privacy_mode"
        private const val CHAT_COMMAND_KEY = "chat_command"
        private const val FALLBACK_SMS_KEY = "fallback_sms"
        private const val CHARGER_STATUS_KEY = "charger_status"
        private const val BATTERY_MONITORING_KEY = "battery_monitoring_switch"
        private const val DISPLAY_DUAL_SIM_KEY = "display_dual_sim_display_name"
        private const val VERIFICATION_CODE_KEY = "verification_code"
        private const val CHAT_ID_KEY = "chat_id"
        private const val BOT_TOKEN_KEY = "bot_token"
        private const val TRUSTED_PHONE_NUMBER_KEY = "trusted_phone_number"
        private const val INITIALIZED_KEY = "initialized"
        private const val PRIVACY_DIALOG_AGREE_KEY = "privacy_dialog_agree"
    }
    private var settings: Settings = Settings(
        isDnsOverHttp = sharedPreferences.getBoolean(DNS_SWITCH_KEY, true),
        isPrivacyMode = sharedPreferences.getBoolean(PRIVACY_MODE_KEY, false),
        isChatCommand = sharedPreferences.getBoolean(CHAT_COMMAND_KEY, false),
        isFallbackSms = sharedPreferences.getBoolean(FALLBACK_SMS_KEY, false),
        isChargerStatus = sharedPreferences.getBoolean(CHARGER_STATUS_KEY, false),
        isBatteryMonitoring = sharedPreferences.getBoolean(BATTERY_MONITORING_KEY, false),
        isDisplayDualSim = sharedPreferences.getBoolean(DISPLAY_DUAL_SIM_KEY, false),
        isVerificationCode = sharedPreferences.getBoolean(VERIFICATION_CODE_KEY, false),
        chatId = sharedPreferences.getString(CHAT_ID_KEY, "").orEmpty(),
        botToken = sharedPreferences.getString(BOT_TOKEN_KEY, "").orEmpty(),
        trustedPhoneNumber = sharedPreferences.getString(TRUSTED_PHONE_NUMBER_KEY, "").orEmpty(),
    )

    override fun getSettings(): Settings = settings
    override fun setSettings(newSettings: Settings) {
        sharedPreferences.edit().apply {
            putBoolean(DNS_SWITCH_KEY, newSettings.isDnsOverHttp)
            putBoolean(PRIVACY_MODE_KEY, newSettings.isPrivacyMode)
            putBoolean(CHAT_COMMAND_KEY, newSettings.isChatCommand)
            putBoolean(FALLBACK_SMS_KEY, newSettings.isFallbackSms)
            putBoolean(CHARGER_STATUS_KEY, newSettings.isChargerStatus)
            putBoolean(BATTERY_MONITORING_KEY, newSettings.isBatteryMonitoring)
            putBoolean(DISPLAY_DUAL_SIM_KEY, newSettings.isDisplayDualSim)
            putBoolean(VERIFICATION_CODE_KEY, newSettings.isVerificationCode)
            putString(CHAT_ID_KEY, newSettings.chatId)
            putString(BOT_TOKEN_KEY, newSettings.botToken)
            putString(TRUSTED_PHONE_NUMBER_KEY, newSettings.trustedPhoneNumber)

            putBoolean(INITIALIZED_KEY, true)
            apply()
        }

        settings = newSettings
    }

    override fun getInitialized(): Boolean = sharedPreferences.getBoolean(INITIALIZED_KEY, false)
    override fun getPrivacyDialogAgree(): Boolean = sharedPreferences.getBoolean(PRIVACY_DIALOG_AGREE_KEY, false)

    override fun getDohSwitch(): Boolean = settings.isDnsOverHttp
    override fun getPrivacyMode(): Boolean = settings.isPrivacyMode
    override fun getChatCommand(): Boolean = settings.isChatCommand
    override fun getFallbackSms(): Boolean = settings.isFallbackSms
    override fun getChargerStatus(): Boolean = settings.isChargerStatus
    override fun getBatteryMonitoring(): Boolean = settings.isBatteryMonitoring
    override fun getDisplayDualSim(): Boolean = settings.isDisplayDualSim
    override fun getVerificationCode(): Boolean = settings.isVerificationCode
    override fun getChatId(): String = settings.chatId
    override fun getBotToken(): String = settings.botToken
    override fun getTrustedPhoneNumber(): String = settings.trustedPhoneNumber

    override fun setPrivacyDialogAgree(value: Boolean) {
        sharedPreferences.edit { putBoolean(PRIVACY_DIALOG_AGREE_KEY, value) }
    }
}
