package com.airfreshener.telegram_sms.mainScreen

import com.airfreshener.telegram_sms.common.data.PrefsRepository
import com.airfreshener.telegram_sms.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModelDelegateImpl(
    prefsRepository: PrefsRepository,
) : SettingsViewModelDelegate {

    val settings: MutableStateFlow<Settings> = MutableStateFlow(prefsRepository.getSettings())

    override val settingsFlow: StateFlow<Settings> = settings.asStateFlow()

    override fun updateSettings(newSettings: Settings) {
        settings.value = newSettings
    }

    override fun fallbackSmsChanged(checked: Boolean) {
        settings.value = settings.value.copy(isFallbackSms = checked)
    }

    override fun chargerStatusChanged(checked: Boolean) {
        settings.value = settings.value.copy(isChargerStatus = checked)
    }

    override fun chatCommandChanged(checked: Boolean) {
        settings.value = settings.value.copy(
            isChatCommand = checked,
            isPrivacyMode = settings.value.chatId.isNotEmpty() && checked && settings.value.isPrivacyMode,
        )
    }

    override fun displayDualSimChanged(checked: Boolean) {
        settings.value = settings.value.copy(isDisplayDualSim = checked)
    }

    override fun verificationCodeChecked(checked: Boolean) {
        settings.value = settings.value.copy(isVerificationCode = checked)
    }

    override fun privacyModeChanged(checked: Boolean) {
        settings.value = settings.value.copy(isPrivacyMode = checked)
    }

    override fun trustedPhoneChanged(value: String) {
        if (value == settings.value.trustedPhoneNumber) return

        settings.value = settings.value.copy(
            trustedPhoneNumber = value,
            isFallbackSms = value.isNotEmpty() && settings.value.isFallbackSms,
        )
    }

    override fun chatIdChanged(value: String) {
        if (value == settings.value.chatId) return
        settings.value = settings.value.copy(
            chatId = value,
            isPrivacyMode = value.isNotEmpty() && settings.value.isChatCommand && settings.value.isPrivacyMode,
        )
    }

    override fun botTokenChanged(value: String) {
        if (value == settings.value.botToken) return
        settings.value = settings.value.copy(
            botToken = value,
        )
    }

}
