package com.airfreshener.telegram_sms.mainScreen

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.airfreshener.telegram_sms.R
import com.airfreshener.telegram_sms.common.data.LogRepository
import com.airfreshener.telegram_sms.common.data.PrefsRepository
import com.airfreshener.telegram_sms.common.data.StringsProvider
import com.airfreshener.telegram_sms.migration.UpdateVersion1
import com.airfreshener.telegram_sms.model.PollingJson
import com.airfreshener.telegram_sms.model.RequestMessage
import com.airfreshener.telegram_sms.model.Settings
import com.airfreshener.telegram_sms.model.TelegramChat
import com.airfreshener.telegram_sms.utils.Consts
import com.airfreshener.telegram_sms.utils.Logger
import com.airfreshener.telegram_sms.utils.NetworkUtils
import com.airfreshener.telegram_sms.utils.OkHttpUtils.toRequestBody
import com.airfreshener.telegram_sms.utils.PaperUtils.DEFAULT_BOOK
import com.airfreshener.telegram_sms.utils.PaperUtils.SYSTEM_BOOK
import com.airfreshener.telegram_sms.utils.PaperUtils.tryRead
import com.airfreshener.telegram_sms.utils.ServiceUtils
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

class MainViewModel(
    private val stringsProvider: StringsProvider,
    private val appContext: Context,
    private val prefsRepository: PrefsRepository,
    private val settingsViewModelDelegate: SettingsViewModelDelegate,
    private val logRepository: LogRepository,
    private val logger: Logger,
) : ViewModel(), SettingsViewModelDelegate by settingsViewModelDelegate {

    private val _settings: MutableStateFlow<Settings> = MutableStateFlow(prefsRepository.getSettings())
    val settings: StateFlow<Settings> = settingsViewModelDelegate.settingsFlow

    private val _loading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: Flow<Boolean> = _loading.asStateFlow()
    val showPrivacyDialog: MutableSharedFlow<Unit> = MutableSharedFlow()
    val showSnackBar: MutableSharedFlow<String> = MutableSharedFlow()
    val showSelectChatList: MutableSharedFlow<List<TelegramChat>> = MutableSharedFlow()

    init {
        if (!prefsRepository.getPrivacyDialogAgree()) {
            viewModelScope.launch { showPrivacyDialog.emit(Unit) }
        }
        val settings = prefsRepository.getSettings()
        if (prefsRepository.getInitialized()) {
            updateConfig()
            checkVersionUpgrade(resetLog = true)
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
            _loading.value = true
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
            _loading.value = false
            if (result.isSuccess && result.getOrNull()?.code == 200) {
                if (newSettings.botToken != botTokenSaved) {
                    logger.i(
                        TAG, "onResponse: The current bot token does not match the " +
                                "saved bot token, clearing the message database."
                    )
                    DEFAULT_BOOK.destroy()
                }
                SYSTEM_BOOK.write("version", Consts.SYSTEM_CONFIG_VERSION)
                checkVersionUpgrade( resetLog = false)

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
                val resultObj = JsonParser.parseString(result.getOrNull()?.body?.string()).asJsonObject
                val errorMessage = errorHead + (resultObj?.get("description") ?: result.exceptionOrNull()?.message)
                logger.e(TAG, errorMessage, result.exceptionOrNull())
                showSnackBar(errorMessage)
            }
        }
    }

    fun onGetIdClicked() {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = settings.value
            if (settings.botToken.isEmpty()) {
                showSnackBar(R.string.token_not_configure)
                return@launch
            }
            _loading.value = true

            Thread { ServiceUtils.stopAllServices(appContext) }.start()

            val requestUri = NetworkUtils.getUrl(settings.botToken, "getUpdates")
            val okhttpClient = NetworkUtils.getOkhttpObj(settings)
                .newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val requestBody = PollingJson()
            requestBody.timeout = 60
            val body = requestBody.toRequestBody()
            val request: Request =
                Request.Builder()
                    .url(requestUri)
                    .method("POST", body)
                    .build()
            val call = okhttpClient.newCall(request)
            val errorHead = "Get chat ID failed: "
            logger.d(TAG, "body: " + requestBody)
            val result = runCatching { call.execute() }
            _loading.value = false
            val responseBodyStr = result.getOrNull()?.body?.string()
            logger.d(TAG, "response: " + responseBodyStr)
            val responseJson = runCatching {
                JsonParser.parseString(responseBodyStr).asJsonObject
            }.getOrNull()
            if (result.isSuccess && result.getOrNull()?.code == 200) {
                val chatsJsonArray = responseJson?.getAsJsonArray("result")
                if (chatsJsonArray == null || chatsJsonArray.size() == 0) {
                    showSnackBar(R.string.unable_get_recent)
                    return@launch
                }
                val chatsList = parseChats(chatsJsonArray)
                showSelectChatList.emit(chatsList)
            } else {
                val errorMessage = errorHead + (responseJson?.get("description")?.asString
                    ?: result.getOrNull()?.message)
                logger.e(TAG, errorMessage, result.exceptionOrNull())
                showSnackBar(errorMessage)
            }
        }
    }

    fun onChatSelected(chat: TelegramChat) {
        _settings.value = _settings.value.copy(chatId = chat.id)
    }

    private fun parseChats(chatsJsonArray: JsonArray): List<TelegramChat> {
        val chatsList = ArrayList<TelegramChat>()
        val chatIdsSet = HashSet<String>()
        for (item in chatsJsonArray) {
            val itemObj = item.asJsonObject
            if (itemObj.has("message")) {
                val messageObj = itemObj["message"].asJsonObject
                val chatObj = messageObj["chat"].asJsonObject
                if (!chatIdsSet.contains(chatObj["id"].asString)) {
                    var username = ""
                    chatObj["username"]?.asString?.let { username = it }
                    chatObj["title"]?.asString?.let { username = it }
                    if (username == "" && !chatObj.has("username")) {
                        chatObj["first_name"]?.asString?.let { username = it }
                        chatObj["last_name"]?.asString?.let { username += " $it" }
                    }
                    val title = username + " (" + chatObj["type"].asString + ")"
                    val id = chatObj["id"].asString
                    chatsList += TelegramChat(id = id, title = title)
                    chatIdsSet += id
                }
            }
            if (itemObj.has("channel_post")) {
                val messageObj = itemObj["channel_post"].asJsonObject
                val chatObj = messageObj["chat"].asJsonObject
                if (!chatIdsSet.contains(chatObj["id"].asString)) {
                    val title = chatObj["title"].asString + " (Channel)"
                    val id = chatObj["id"].asString
                    chatsList += TelegramChat(id = id, title = title)
                    chatIdsSet += id
                }
            }
        }
        return chatsList
    }
    private fun checkVersionUpgrade(resetLog: Boolean) {
        val context = appContext
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
            else -> logger.i(TAG, "update_config: Can't find a version that can be updated")
        }
    }

    companion object {
        private val TAG = MainViewModel::class.java.simpleName
    }
}
