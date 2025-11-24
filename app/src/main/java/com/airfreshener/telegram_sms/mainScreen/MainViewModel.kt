package com.airfreshener.telegram_sms.mainScreen

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airfreshener.telegram_sms.R
import com.airfreshener.telegram_sms.common.data.LogRepository
import com.airfreshener.telegram_sms.common.data.PrefsRepository
import com.airfreshener.telegram_sms.common.data.StringsProvider
import com.airfreshener.telegram_sms.migration.UpdateVersion1
import com.airfreshener.telegram_sms.model.RequestMessage
import com.airfreshener.telegram_sms.model.Settings
import com.airfreshener.telegram_sms.utils.Consts
import com.airfreshener.telegram_sms.utils.NetworkUtils
import com.airfreshener.telegram_sms.utils.OkHttpUtils.toRequestBody
import com.airfreshener.telegram_sms.utils.PaperUtils.DEFAULT_BOOK
import com.airfreshener.telegram_sms.utils.PaperUtils.SYSTEM_BOOK
import com.airfreshener.telegram_sms.utils.PaperUtils.tryRead
import com.airfreshener.telegram_sms.utils.ServiceUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request

class MainViewModel(
    private val stringsProvider: StringsProvider,
    private val appContext: Context,
    private val prefsRepository: PrefsRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _settings: MutableStateFlow<Settings> = MutableStateFlow(prefsRepository.getSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _loading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: Flow<Boolean> = _loading.asStateFlow()
    val showPrivacyDialog: MutableSharedFlow<Unit> = MutableSharedFlow()
    val showSnackBar: MutableSharedFlow<String> = MutableSharedFlow()

    init {
        if (!prefsRepository.getPrivacyDialogAgree()) {
            viewModelScope.launch { showPrivacyDialog.emit(Unit) }
        }
        val settings = prefsRepository.getSettings()
        if (prefsRepository.getInitialized()) {
            updateConfig()
            checkVersionUpgrade(logRepository, appContext, true)
            ServiceUtils.startServices(appContext, settings)
        }
    }

    fun batteryMonitoringChecked(checked: Boolean) {
        _settings.value = _settings.value.copy(
            isBatteryMonitoring = checked,
            isChargerStatus = checked && _settings.value.isChargerStatus
        )
    }

    fun dnsOverHttpChecked(checked: Boolean) {
        _settings.value = _settings.value.copy(isDnsOverHttp = checked)
    }

    fun fallbackSmsChanged(checked: Boolean) {
        _settings.value = _settings.value.copy(isFallbackSms = checked)
    }

    fun chargerStatusChanged(checked: Boolean) {
        _settings.value = _settings.value.copy(isChargerStatus = checked)
    }

    fun chatCommandChanged(checked: Boolean) {
        _settings.value = _settings.value.copy(
            isChatCommand = checked,
            isPrivacyMode = _settings.value.chatId.isNotEmpty() && checked && _settings.value.isPrivacyMode,
        )
    }

    fun displayDualSimChanged(checked: Boolean) {
        _settings.value = _settings.value.copy(isDisplayDualSim = checked)
    }

    fun verificationCodeChecked(checked: Boolean) {
        _settings.value = _settings.value.copy(isVerificationCode = checked)
    }

    fun privacyModeChanged(checked: Boolean) {
        _settings.value = _settings.value.copy(isPrivacyMode = checked)
    }

    fun trustedPhoneNumberChanged(value: String) {
        if (value == _settings.value.trustedPhoneNumber) return

        _settings.value = _settings.value.copy(
            trustedPhoneNumber = value,
            isFallbackSms = value.isNotEmpty() && _settings.value.isFallbackSms,
        )
    }

    fun chatIdChanged(value: String) {
        if (value == _settings.value.chatId) return
        _settings.value = _settings.value.copy(
            chatId = value,
            isPrivacyMode = value.isNotEmpty() && _settings.value.isChatCommand && _settings.value.isPrivacyMode,
        )
    }

    fun botTokenChanged(value: String) {
        if (value == _settings.value.botToken) return
        _settings.value = _settings.value.copy(
            botToken = value,
        )
    }

    fun qrCodeScanned(jsonConfig: JsonObject) {
        val isBatteryMonitoring = jsonConfig["battery_monitoring_switch"].asBoolean
        val isFallbackSms = jsonConfig["fallback_sms"].asBoolean
        val trustedPhoneNumber = jsonConfig["trusted_phone_number"].asString
        val newSettings = Settings(
            botToken = jsonConfig["bot_token"].asString,
            chatId = jsonConfig["chat_id"].asString,
            isBatteryMonitoring = isBatteryMonitoring,
            isVerificationCode = jsonConfig["verification_code"].asBoolean,
            isChargerStatus = isBatteryMonitoring && jsonConfig["charger_status"].asBoolean,
            isChatCommand = jsonConfig["chat_command"].asBoolean,
            isPrivacyMode = jsonConfig["privacy_mode"].asBoolean,
            trustedPhoneNumber = trustedPhoneNumber,
            isFallbackSms = isFallbackSms && trustedPhoneNumber.isNotEmpty(),
            isDnsOverHttp = true, // TODO
            isDisplayDualSim = false // TODO
        )
        _settings.value = newSettings
    }

    fun onStopClicked() {
        val appContext = appContext
        Thread { ServiceUtils.stopAllServices(appContext) }.start()
    }

    private suspend fun showSnackBar(str: String) {
        showSnackBar.emit(str)
    }
    private suspend fun showSnackBar(resId: Int) {
        showSnackBar.emit(stringsProvider.getString(resId))
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            val appContext = appContext
            val botTokenSaved = prefsRepository.getSettings().botToken
            val newSettings = _settings.value
            if (newSettings.botToken.isEmpty() || newSettings.chatId.isEmpty()) {
                showSnackBar(R.string.chat_id_or_token_not_config)
                return@launch
            }
            if (newSettings.isFallbackSms && newSettings.trustedPhoneNumber.isEmpty()) {
                showSnackBar(R.string.trusted_phone_number_empty)
                return@launch
            }
            if (!prefsRepository.getPrivacyDialogAgree()) {
                showPrivacyDialog.emit(Unit)
                return@launch
            }

            // val progressDialog = ProgressDialog(this)
            // progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER)
            // progressDialog.setTitle(appContext.getString(R.string.connect_wait_title))
            // progressDialog.setMessage(appContext.getString(R.string.connect_wait_message))
            // progressDialog.isIndeterminate = false
            // progressDialog.setCancelable(false)
            // progressDialog.show()

            val requestUri = NetworkUtils.getUrl(newSettings.botToken, "sendMessage")
            val requestBody = RequestMessage().apply {
                chat_id = newSettings.chatId
                text = appContext.getString(R.string.success_connect)
            }
            val body = requestBody.toRequestBody()
            val okhttpClient = NetworkUtils.getOkhttpObj(newSettings)
            val request: Request = Request.Builder().url(requestUri).post(body).build()
            val call = okhttpClient.newCall(request)
            val errorHead = "Send message failed: "
            val result = runCatching { call.execute() }
            // progressDialog.cancel()
            if (result.isSuccess && result.getOrNull()?.code == 200) {
                if (newSettings.botToken != botTokenSaved) {
                    Log.i(
                        TAG, "onResponse: The current bot token does not match the " +
                                "saved bot token, clearing the message database."
                    )
                    DEFAULT_BOOK.destroy()
                }
                SYSTEM_BOOK.write("version", Consts.SYSTEM_CONFIG_VERSION)
                checkVersionUpgrade(logRepository, appContext, false)

                prefsRepository.setSettings(newSettings)

                Thread {
                    ServiceUtils.stopAllServices(appContext)
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    ServiceUtils.startServices(appContext, newSettings)
                }.start()
                showSnackBar(R.string.success)
            } else {
                result.exceptionOrNull()?.printStackTrace()
                val resultObj = JsonParser.parseString(result.getOrNull()?.body?.string()).asJsonObject
                val errorMessage = errorHead + (resultObj?.get("description") ?: result.exceptionOrNull()?.message)
                logRepository.writeLog(errorMessage)
                showSnackBar(errorMessage)
            }
        }
    }

    private fun checkVersionUpgrade(logRepository: LogRepository, context: Context, resetLog: Boolean) {
        val versionCode = SYSTEM_BOOK.tryRead("version_code", 0)
        val packageManager = context.packageManager
        val packageInfo: PackageInfo
        val currentVersionCode: Int
        try {
            packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            currentVersionCode = packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            return
        }
        if (versionCode != currentVersionCode) {
            if (resetLog) {
                logRepository.resetLogFile()
            }
            SYSTEM_BOOK.write("version_code", currentVersionCode)
        }
    }

    private fun updateConfig() {
        val storeVersion = SYSTEM_BOOK.tryRead("version", 0)
        if (storeVersion == Consts.SYSTEM_CONFIG_VERSION) {
            UpdateVersion1().checkError()
            return
        }
        when (storeVersion) {
            0 -> UpdateVersion1().update()
            else -> Log.i(TAG, "update_config: Can't find a version that can be updated")
        }
    }

    companion object {
        private val TAG = MainViewModel::class.java.simpleName
    }
}
