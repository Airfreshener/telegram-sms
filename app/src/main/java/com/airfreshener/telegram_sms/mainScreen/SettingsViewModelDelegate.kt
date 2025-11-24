package com.airfreshener.telegram_sms.mainScreen

import com.airfreshener.telegram_sms.model.Settings
import kotlinx.coroutines.flow.StateFlow

interface SettingsViewModelDelegate {
    val settingsFlow: StateFlow<Settings>

    fun updateSettings(newSettings: Settings)
    fun fallbackSmsChanged(checked: Boolean)
    fun chargerStatusChanged(checked: Boolean)
    fun chatCommandChanged(checked: Boolean)
    fun displayDualSimChanged(checked: Boolean)
    fun verificationCodeChecked(checked: Boolean)
    fun privacyModeChanged(checked: Boolean)
    fun trustedPhoneNumberChanged(value: String)
    fun chatIdChanged(value: String)
    fun botTokenChanged(value: String)
}
