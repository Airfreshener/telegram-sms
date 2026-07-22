package com.airfreshener.telegram_sms.model

@Suppress("unused", "PropertyName")
class ConfigurationQrCodeDTO(
    val bot_token: String?,
    val chat_id: String?,
    val trusted_phone_number: String?,
    val fallback_sms: Boolean,
    val chat_command: Boolean,
    val battery_monitoring_switch: Boolean,
    val charger_status: Boolean,
    val verification_code: Boolean,
    val privacy_mode: Boolean,
)
