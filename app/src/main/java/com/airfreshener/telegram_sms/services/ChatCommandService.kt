package com.airfreshener.telegram_sms.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.WifiLock
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import com.airfreshener.telegram_sms.R
import com.airfreshener.telegram_sms.model.PollingJson
import com.airfreshener.telegram_sms.model.ReplyMarkupKeyboard.InlineKeyboardButton
import com.airfreshener.telegram_sms.model.ReplyMarkupKeyboard.KeyboardMarkup
import com.airfreshener.telegram_sms.model.ReplyMarkupKeyboard.getInlineKeyboardObj
import com.airfreshener.telegram_sms.model.RequestMessage
import com.airfreshener.telegram_sms.model.SmsRequestInfo
import com.airfreshener.telegram_sms.utils.Consts
import com.airfreshener.telegram_sms.utils.LogUtils.readLog
import com.airfreshener.telegram_sms.utils.LogUtils.writeLog
import com.airfreshener.telegram_sms.utils.NetworkUtils.checkNetworkStatus
import com.airfreshener.telegram_sms.utils.NetworkUtils.getOkhttpObj
import com.airfreshener.telegram_sms.utils.NetworkUtils.getUrl
import com.airfreshener.telegram_sms.utils.OkHttpUtils.toRequestBody
import com.airfreshener.telegram_sms.utils.OtherUtils.getActiveCard
import com.airfreshener.telegram_sms.utils.OtherUtils.getDualSimCardDisplay
import com.airfreshener.telegram_sms.utils.OtherUtils.getMessageId
import com.airfreshener.telegram_sms.utils.OtherUtils.getNotificationObj
import com.airfreshener.telegram_sms.utils.OtherUtils.getSendPhoneNumber
import com.airfreshener.telegram_sms.utils.OtherUtils.getSimDisplayName
import com.airfreshener.telegram_sms.utils.OtherUtils.getSubId
import com.airfreshener.telegram_sms.utils.OtherUtils.isPhoneNumber
import com.airfreshener.telegram_sms.utils.OtherUtils.parseStringToLong
import com.airfreshener.telegram_sms.utils.PaperUtils
import com.airfreshener.telegram_sms.utils.PaperUtils.getDefaultBook
import com.airfreshener.telegram_sms.utils.PaperUtils.getSendTempBook
import com.airfreshener.telegram_sms.utils.ResendUtils.addResendLoop
import com.airfreshener.telegram_sms.utils.ServiceUtils.stopAllService
import com.airfreshener.telegram_sms.utils.SmsUtils.sendFallbackSms
import com.airfreshener.telegram_sms.utils.SmsUtils.sendSms
import com.airfreshener.telegram_sms.utils.UssdUtils.sendUssd
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.Objects
import java.util.concurrent.TimeUnit

class ChatCommandService : Service() {

    private var chatId: String? = null
    private var botToken: String? = null
    private var context: Context? = null
    private var okHttpClient: OkHttpClient? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var wakelock: WakeLock? = null
    private var wifiLock: WifiLock? = null
    private var botUsername: String? = ""
    private var privacyMode = false

    private fun receiveHandle(resultObj: JsonObject, getIdOnly: Boolean) {
        val appContext = applicationContext
        val updateId = resultObj["update_id"].asLong
        offset = updateId + 1
        if (getIdOnly) {
            Log.d(TAG, "receive_handle: get_id_only")
            return
        }
        var messageType = ""
        val requestBody = RequestMessage()
        requestBody.chat_id = chatId
        var messageObj: JsonObject? = null
        if (resultObj.has("message")) {
            messageObj = resultObj["message"].asJsonObject
            messageType = messageObj["chat"].asJsonObject["type"].asString
        }
        if (resultObj.has("channel_post")) {
            messageType = "channel"
            messageObj = resultObj["channel_post"].asJsonObject
        }
        var callbackData: String? = null
        if (resultObj.has("callback_query")) {
            messageType = "callback_query"
            val callbackQuery = resultObj["callback_query"].asJsonObject
            callbackData = callbackQuery["data"].asString
        }
        if (messageType == "callback_query" && sendSmsNextStatus != Consts.SEND_SMS_STATUS.STANDBY_STATUS) {
            var slot = getSendTempBook().read("slot", -1)!!
            val messageId = getSendTempBook().read("message_id", -1L)!!
            val to = getSendTempBook().read("to", "")
            val content = getSendTempBook().read("content", "")
            assert(callbackData != null)
            if (callbackData != Consts.CALLBACK_DATA_VALUE.SEND) {
                setSmsSendStatusStandby()
                val requestUri = getUrl(botToken!!, "editMessageText")
                val dualSim = getDualSimCardDisplay(
                    appContext,
                    slot,
                    prefs!!.getBoolean("display_dual_sim_display_name", false)
                )
                val sendContent = """
                    [$dualSim${appContext.getString(R.string.send_sms_head)}]
                    ${appContext.getString(R.string.to)}$to
                    ${appContext.getString(R.string.content)}$content
                    """.trimIndent()
                requestBody.text = """
                    $sendContent
                    ${appContext.getString(R.string.status)}${appContext.getString(R.string.cancel_button)}
                    """.trimIndent()
                requestBody.message_id = messageId
                val body = requestBody.toRequestBody()
                val okhttpClient = getOkhttpObj(prefs!!.getBoolean("doh_switch", true))
                val request: Request = Request.Builder().url(requestUri).method("POST", body).build()
                val call = okhttpClient.newCall(request)
                try {
                    val response = call.execute()
                    val responseBody = response.body
                    if (response.code != 200 || responseBody == null) {
                        throw IOException(response.code.toString())
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                    writeLog(appContext, "failed to send message:" + e.message)
                }
                return
            }
            var subId = -1
            if (getActiveCard(appContext) == 1) {
                slot = -1
            } else {
                subId = getSubId(appContext, slot)
            }
            sendSms(appContext, to!!, content!!, slot, subId, messageId)
            setSmsSendStatusStandby()
            return
        }
        if (messageObj == null) {
            writeLog(appContext, "Request type is not allowed by security policy.")
            return
        }
        var fromObj: JsonObject? = null
        val messageTypeIsPrivate = messageType == "private"
        if (messageObj.has("from")) {
            fromObj = messageObj["from"].asJsonObject
            if (!messageTypeIsPrivate && fromObj["is_bot"].asBoolean) {
                Log.i(TAG, "receive_handle: receive from bot.")
                return
            }
        }
        if (messageObj.has("chat")) {
            fromObj = messageObj["chat"].asJsonObject
        }
        assert(fromObj != null)
        val fromId = fromObj!!["id"].asString
        if (chatId != fromId) {
            writeLog(appContext, "Chat ID[$fromId] not allow.")
            return
        }
        var command = ""
        var commandBotUsername = ""
        var requestMsg = ""
        if (messageObj.has("text")) {
            requestMsg = messageObj["text"].asString
        }
        if (messageObj.has("reply_to_message")) {
            val saveItem = getDefaultBook().read<SmsRequestInfo>(
                messageObj["reply_to_message"].asJsonObject["message_id"].asString,
                null
            )
            if (saveItem != null && !requestMsg.isEmpty()) {
                val phoneNumber = saveItem.phone ?: "-"
                val cardSlot = saveItem.card
                sendSmsNextStatus = Consts.SEND_SMS_STATUS.WAITING_TO_SEND_STATUS
                getSendTempBook()
                    .write("slot", cardSlot)
                    .write("to", phoneNumber)
                    .write("content", requestMsg)
            }
            if (!messageTypeIsPrivate) {
                Log.i(TAG, "receive_handle: The message id could not be found, ignored.")
                return
            }
        }
        if (messageObj.has("entities")) {
            val tempCommand: String
            val tempCommandLowercase: String
            val entitiesArr = messageObj["entities"].asJsonArray
            val entitiesObjCommand = entitiesArr[0].asJsonObject
            if (entitiesObjCommand["type"].asString == "bot_command") {
                val commandOffset = entitiesObjCommand["offset"].asInt
                val commandEndOffset = commandOffset + entitiesObjCommand["length"].asInt
                tempCommand =
                    requestMsg.substring(commandOffset, commandEndOffset).trim { it <= ' ' }
                tempCommandLowercase = tempCommand.lowercase(Locale.getDefault()).replace("_", "")
                command = tempCommandLowercase
                if (tempCommandLowercase.contains("@")) {
                    val commandAtLocation = tempCommandLowercase.indexOf("@")
                    command = tempCommandLowercase.substring(0, commandAtLocation)
                    commandBotUsername = tempCommand.substring(commandAtLocation + 1)
                }
            }
        }
        if (!messageTypeIsPrivate && privacyMode && commandBotUsername != botUsername) {
            Log.i(TAG, "receive_handle: Privacy mode, no username found.")
            return
        }
        Log.d(TAG, "receive_handle: $command")
        var hasCommand = false
        when (command) {
            "/help", "/start", "/commandlist" -> {
                var smsCommand = appContext.getString(R.string.sendsms)
                if (getActiveCard(appContext) == 2) {
                    smsCommand = appContext.getString(R.string.sendsms_dual)
                }
                smsCommand += """
                
                ${appContext.getString(R.string.get_spam_sms)}
                """.trimIndent()
                var ussdCommand = ""
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ussdCommand = """
                            ${getString(R.string.send_ussd_command)}
                            """.trimIndent()
                        if (getActiveCard(appContext) == 2) {
                            ussdCommand = """
                                ${appContext.getString(R.string.send_ussd_dual_command)}
                                """.trimIndent()
                        }
                    }
                }
                if (command == "/commandlist") {
                    requestBody.text = """${appContext.getString(R.string.available_command)}
$smsCommand$ussdCommand""".replace("/", "")
                } else {
                    var result = """
                        ${appContext.getString(R.string.system_message_head)}
                        ${appContext.getString(R.string.available_command)}
                        $smsCommand$ussdCommand
                        """
                        .trimIndent()
                    if (!messageTypeIsPrivate && privacyMode && botUsername != "") {
                        result = result.replace(" -", "@$botUsername -")
                    }
                    requestBody.text = result
                    hasCommand = true
                }
            }

            "/ping", "/getinfo" -> {
                var cardInfo = ""
                if (ActivityCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.READ_PHONE_STATE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    cardInfo = "SIM: ${getSimDisplayName(appContext, 0)}"
                    if (getActiveCard(appContext) == 2) {
                        cardInfo += "\nSIM2: ${getSimDisplayName(appContext, 1)}"
                    }
                }
                val spamList = getDefaultBook().read("spam_sms_list", ArrayList<String>())!!
                val spamCount = "${appContext.getString(R.string.spam_count_title)}${spamList.size}"
                requestBody.text = """
                    ${appContext.getString(R.string.system_message_head)}
                    ${appContext.getString(R.string.current_battery_level)}${ChatServiceUtils.getBatteryInfo(appContext)}
                    ${appContext.getString(R.string.current_network_connection_status)}${ChatServiceUtils.getNetworkType(appContext)}
                    $cardInfo
                    $spamCount
                    """.trimIndent()
                hasCommand = true
            }

            "/log" -> {
                val cmdList: Array<String?> =
                    requestMsg.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                var line = 10
                if (cmdList.size == 2 && isNumeric(cmdList[1])) {
                    assert(cmdList[1] != null)
                    var lineCommand = Integer.getInteger(cmdList[1])
                    if (lineCommand > 50) {
                        lineCommand = 50
                    }
                    line = lineCommand
                }
                requestBody.text = appContext.getString(R.string.system_message_head) + readLog(appContext, line)
                hasCommand = true
            }

            "/sendussd", "/sendussd1", "/sendussd2" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (ActivityCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.CALL_PHONE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val commandList =
                            requestMsg.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()
                        var subId = -1
                        if (getActiveCard(appContext) == 2) {
                            if (command == "/sendussd2") {
                                subId = getSubId(appContext, 1)
                            }
                        }
                        if (commandList.size == 2) {
                            sendUssd(appContext, commandList[1], subId)
                            return
                        }
                    }
                }
                requestBody.text = """
                    ${appContext.getString(R.string.system_message_head)}
                    ${appContext.getString(R.string.unknown_command)}
                    """.trimIndent()
            }

            "/getspamsms" -> {
                val spamSmsList = getDefaultBook().read("spam_sms_list", ArrayList<String>())!!
                if (spamSmsList.size == 0) {
                    requestBody.text = """
                        ${appContext.getString(R.string.system_message_head)}
                        ${appContext.getString(R.string.no_spam_history)}
                        """.trimIndent()
                } else {
                    sendSpamSmsMessage(spamSmsList)
                    return
                }
            }

            "/sendsms", "/sendsms1", "/sendsms2" -> {
                val msgSendList =
                    requestMsg.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (msgSendList.size > 2) {
                    val msgSendTo = getSendPhoneNumber(msgSendList[1])
                    if (isPhoneNumber(msgSendTo)) {
                        val msgSendContent = StringBuilder()
                        var i = 2
                        while (i < msgSendList.size) {
                            if (msgSendList.size != 3 && i != 2) {
                                msgSendContent.append("\n")
                            }
                            msgSendContent.append(msgSendList[i])
                            ++i
                        }
                        if (getActiveCard(appContext) == 1) {
                            sendSms(appContext, msgSendTo, msgSendContent.toString(), -1, -1)
                            return
                        }
                        var sendSlot = -1
                        if (getActiveCard(appContext) > 1) {
                            sendSlot = 0
                            if (command == "/sendsms2") {
                                sendSlot = 1
                            }
                        }
                        val subId = getSubId(appContext, sendSlot)
                        if (subId != -1) {
                            sendSms(
                                appContext,
                                msgSendTo,
                                msgSendContent.toString(),
                                sendSlot,
                                subId
                            )
                            return
                        }
                    }
                } else {
                    sendSmsNextStatus = Consts.SEND_SMS_STATUS.PHONE_INPUT_STATUS
                    var sendSlot = -1
                    if (getActiveCard(appContext) > 1) {
                        sendSlot = 0
                        if (command == "/sendsms2") {
                            sendSlot = 1
                        }
                    }
                    getSendTempBook().write("slot", sendSlot)
                }
                requestBody.text = """
                    [${appContext.getString(R.string.send_sms_head)}]
                    ${appContext.getString(R.string.failed_to_get_information)}
                    """.trimIndent()
            }

            else -> {
                if (!messageTypeIsPrivate && sendSmsNextStatus == -1) {
                    Log.i(
                        TAG,
                        "receive_handle: The conversation is not Private and does not prompt an error."
                    )
                    return
                }
                requestBody.text = """
                    ${appContext.getString(R.string.system_message_head)}
                    ${appContext.getString(R.string.unknown_command)}
                    """.trimIndent()
            }
        }
        if (hasCommand) {
            setSmsSendStatusStandby()
        }
        if (!hasCommand && sendSmsNextStatus != -1) {
            Log.i(TAG, "receive_handle: Enter the interactive SMS sending mode.")
            var dualSim = ""
            val sendSlotTemp = getSendTempBook().read("slot", -1)!!
            if (sendSlotTemp != -1) {
                dualSim = "SIM" + (sendSlotTemp + 1) + " "
            }
            val head = "[" + dualSim + appContext.getString(R.string.send_sms_head) + "]"
            var resultSend = appContext.getString(R.string.failed_to_get_information)
            Log.d(TAG, "Sending mode status: $sendSmsNextStatus")
            when (sendSmsNextStatus) {
                Consts.SEND_SMS_STATUS.PHONE_INPUT_STATUS -> {
                    sendSmsNextStatus = Consts.SEND_SMS_STATUS.MESSAGE_INPUT_STATUS
                    resultSend = appContext.getString(R.string.enter_number)
                }

                Consts.SEND_SMS_STATUS.MESSAGE_INPUT_STATUS -> {
                    val tempTo = getSendPhoneNumber(requestMsg)
                    if (isPhoneNumber(tempTo)) {
                        getSendTempBook().write("to", tempTo)
                        resultSend = appContext.getString(R.string.enter_content)
                        sendSmsNextStatus = Consts.SEND_SMS_STATUS.WAITING_TO_SEND_STATUS
                    } else {
                        setSmsSendStatusStandby()
                        resultSend = appContext.getString(R.string.unable_get_phone_number)
                    }
                }

                Consts.SEND_SMS_STATUS.WAITING_TO_SEND_STATUS -> {
                    getSendTempBook().write("content", requestMsg)
                    val keyboardMarkup = KeyboardMarkup()
                    val inlineKeyboardButtons = ArrayList<ArrayList<InlineKeyboardButton>>()
                    inlineKeyboardButtons.add(
                        getInlineKeyboardObj(
                            appContext.getString(R.string.send_button),
                            Consts.CALLBACK_DATA_VALUE.SEND
                        )
                    )
                    inlineKeyboardButtons.add(
                        getInlineKeyboardObj(
                            appContext.getString(R.string.cancel_button),
                            Consts.CALLBACK_DATA_VALUE.CANCEL
                        )
                    )
                    keyboardMarkup.inline_keyboard = inlineKeyboardButtons
                    requestBody.reply_markup = keyboardMarkup
                    resultSend = """
                        ${appContext.getString(R.string.to)}${getSendTempBook().read<Any>("to")}
                        ${appContext.getString(R.string.content)}${
                        getSendTempBook().read(
                            "content",
                            ""
                        )
                    }
                        """.trimIndent()
                    sendSmsNextStatus = Consts.SEND_SMS_STATUS.SEND_STATUS
                }
            }
            requestBody.text = """
                $head
                $resultSend
                """.trimIndent()
        }
        val requestUri = getUrl(botToken!!, "sendMessage")
        val body = requestBody.toRequestBody()
        val sendRequest: Request = Request.Builder().url(requestUri).method("POST", body).build()
        val call = okHttpClient!!.newCall(sendRequest)
        val errorHead = "Send reply failed:"
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                writeLog(appContext, errorHead + e.message)
                addResendLoop(appContext, requestBody.text)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseString = response.body?.string()
                if (response.code != 200) {
                    writeLog(appContext, errorHead + response.code + " " + responseString)
                    addResendLoop(appContext, requestBody.text)
                }
                if (sendSmsNextStatus == Consts.SEND_SMS_STATUS.SEND_STATUS) {
                    getSendTempBook().write("message_id", getMessageId(responseString))
                }
            }
        })
    }

    private fun sendSpamSmsMessage(spamSmsList: ArrayList<String>) {
        Thread {
            if (checkNetworkStatus(applicationContext)) {
                val okhttpClient = getOkhttpObj(prefs!!.getBoolean("doh_switch", true))
                for (item in spamSmsList) {
                    val sendSmsRequestBody = RequestMessage()
                    sendSmsRequestBody.chat_id = chatId
                    sendSmsRequestBody.text = item
                    val requestUri = getUrl(botToken!!, "sendMessage")
                    val body = sendSmsRequestBody.toRequestBody()
                    val requestObj: Request = Request.Builder().url(requestUri).method("POST", body).build()
                    val call = okhttpClient.newCall(requestObj)
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            Log.d(TAG, "onFailure: " + e.message)
                            e.printStackTrace()
                            writeLog(context, e.message.orEmpty())
                        }

                        override fun onResponse(call: Call, response: Response) {
                            Log.d(TAG, "onResponse: " + response.code)
                        }
                    })
                    val resendListLocal =
                        getDefaultBook().read("spam_sms_list", ArrayList<String?>())!!
                    resendListLocal.remove(item)
                    getDefaultBook().write("spam_sms_list", resendListLocal)
                }
            }
            writeLog(context, "Send spam message is complete.")
        }.start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val notification =
            getNotificationObj(applicationContext, getString(R.string.chat_command_service_name))
        startForeground(Consts.ServiceNotifyId.CHAT_COMMAND, notification)
        return START_STICKY
    }

    @SuppressLint("InvalidWakeLockTag", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        PaperUtils.init(applicationContext)
        setSmsSendStatusStandby()
        val prefs = applicationContext.getSharedPreferences("data", MODE_PRIVATE).apply {
            prefs = this
        }
        chatId = prefs.getString("chat_id", "")
        botToken = prefs.getString("bot_token", "")
        okHttpClient = getOkhttpObj(prefs.getBoolean("doh_switch", true))
        privacyMode = prefs.getBoolean("privacy_mode", false)
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "bot_command_polling_wifi").apply {
            setReferenceCounted(false)
            if (!isHeld) acquire()
        }
        val powerManager =
            Objects.requireNonNull(applicationContext.getSystemService(POWER_SERVICE)) as PowerManager
        wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bot_command_polling").apply {
            setReferenceCounted(false)
            if (!isHeld) acquire()
        }
        threadMain = Thread(ThreadMainRunnable(applicationContext)).apply { start() }
        val intentFilter = IntentFilter()
        intentFilter.addAction(Consts.BROADCAST_STOP_SERVICE)
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        broadcastReceiver = BroadcastReceiver()
        registerReceiver(broadcastReceiver, intentFilter)
    }

    private val me: Boolean
        private get() {
            val request_uri = getUrl(botToken!!, "getMe")
            val request: Request = Request.Builder().url(request_uri).build()
            val call = okHttpClient?.newCall(request) ?: return false
            val response: Response = try {
                call.execute()
            } catch (e: IOException) {
                e.printStackTrace()
                writeLog(applicationContext, "Get username failed:" + e.message)
                return false
            }
            if (response.code == 200) {
                val responseBody = response.body
                if (responseBody == null) {
                    Log.d(TAG, "No body on 200 response")
                    return false
                }
                val result: String = try {
                    responseBody.string()
                } catch (e: IOException) {
                    e.printStackTrace()
                    return false
                }
                val resultObj = JsonParser.parseString(result).asJsonObject
                if (resultObj["ok"].asBoolean) {
                    botUsername = resultObj["result"].asJsonObject["username"].asString.apply {
                        getDefaultBook().write("bot_username", this)
                    }
                    Log.d(TAG, "bot_username: $botUsername")
                    writeLog(applicationContext, "Get the bot username: $botUsername")
                }
                return true
            }
            return false
        }

    private fun setSmsSendStatusStandby() {
        Log.d(TAG, "set_sms_send_status_standby: ")
        sendSmsNextStatus = Consts.SEND_SMS_STATUS.STANDBY_STATUS
        getSendTempBook().destroy()
    }

    override fun onDestroy() {
        wifiLock!!.release()
        wakelock!!.release()
        unregisterReceiver(broadcastReceiver)
        stopForeground(true)
        super.onDestroy()
    }

    private inner class ThreadMainRunnable(
        private val appContext: Context
    ) : Runnable {
        override fun run() {
            Log.d(TAG, "run: thread main start")
            if (parseStringToLong(chatId!!) < 0) {
                botUsername = getDefaultBook().read<String>("bot_username", null)
                if (botUsername == null) {
                    while (!me) {
                        writeLog(
                            appContext,
                            "Failed to get bot Username, Wait 5 seconds and try again."
                        )
                        try {
                            Thread.sleep(5000)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }
                Log.i(TAG, "run: The Bot Username is loaded. The Bot Username is: $botUsername")
            }
            while (true) {
                val timeout = 5 * magnification
                val httpTimeout = timeout + 5
                val okHttpClientNew = okHttpClient!!.newBuilder()
                    .readTimeout(httpTimeout.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(httpTimeout.toLong(), TimeUnit.SECONDS)
                    .build()
                Log.d(TAG, "run: Current timeout: " + timeout + "S")
                val requestUri = getUrl(botToken!!, "getUpdates")
                val requestBody = PollingJson()
                requestBody.offset = offset
                requestBody.timeout = timeout
                if (firstRequest) {
                    requestBody.timeout = 0
                    Log.d(TAG, "run: first_request")
                }
                val body = requestBody.toRequestBody()
                val request: Request = Request.Builder().url(requestUri).method("POST", body).build()
                val call = okHttpClientNew.newCall(request)
                var response: Response
                try {
                    response = call.execute()
                    errorMagnification = 1
                } catch (e: IOException) {
                    e.printStackTrace()
                    if (!checkNetworkStatus(appContext)) {
                        writeLog(
                            appContext,
                            "No network connections available, Wait for the network to recover."
                        )
                        errorMagnification = 1
                        magnification = 1
                        Log.d(TAG, "run: break loop.")
                        break
                    }
                    val sleepTime = 5 * errorMagnification
                    writeLog(
                        appContext,
                        "Connection to the Telegram API service failed, try again after $sleepTime seconds."
                    )
                    magnification = 1
                    if (errorMagnification <= 59) {
                        ++errorMagnification
                    }
                    try {
                        Thread.sleep(sleepTime * 1000L)
                    } catch (e1: InterruptedException) {
                        e1.printStackTrace()
                    }
                    continue
                }
                if (response.code == 200) {
                    val responseBody = response.body
                    if (responseBody == null) {
                        Log.d(TAG, "No body on 200 response")
                        continue
                    }
                    val result: String = try {
                        responseBody.string()
                    } catch (e: IOException) {
                        e.printStackTrace()
                        continue
                    }
                    val resultObj = JsonParser.parseString(result).asJsonObject
                    if (resultObj["ok"].asBoolean) {
                        val resultArray = resultObj["result"].asJsonArray
                        for (item in resultArray) {
                            receiveHandle(item.asJsonObject, firstRequest)
                        }
                        firstRequest = false
                    }
                    if (magnification <= 11) {
                        ++magnification
                    }
                } else {
                    Log.d(TAG, "response code: " + response.code)
                    if (response.code == 401) {
                        val responseBody = response.body
                        if (responseBody == null) {
                            Log.d(TAG, "No body on 200 response")
                            continue
                        }
                        val result: String = try {
                            responseBody.string()
                        } catch (e: IOException) {
                            e.printStackTrace()
                            continue
                        }
                        val resultObj = JsonParser.parseString(result).asJsonObject
                        val resultMessage = """
                            ${appContext.getString(R.string.system_message_head)}
                            ${appContext.getString(R.string.error_stop_message)}
                            ${appContext.getString(R.string.error_message_head)}${resultObj["description"].asString}
                            Code: ${response.code}
                            """.trimIndent()
                        sendFallbackSms(appContext, resultMessage, -1)
                        stopAllService(appContext)
                        break
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private inner class BroadcastReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive: " + intent.action)
            assert(intent.action != null)
            when (intent.action) {
                Consts.BROADCAST_STOP_SERVICE -> {
                    Log.i(TAG, "Received stop signal, quitting now...")
                    stopSelf()
                    Process.killProcess(Process.myPid())
                }

                ConnectivityManager.CONNECTIVITY_ACTION -> if (checkNetworkStatus(context)) {
                    if (!threadMain!!.isAlive) {
                        writeLog(context, "Network connections has been restored.")
                        threadMain = Thread(ThreadMainRunnable(context))
                        threadMain!!.start()
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "chat_command_service"
        private var offset: Long = 0
        private var magnification = 1
        private var errorMagnification = 1
        private var prefs: SharedPreferences? = null
        private var sendSmsNextStatus = Consts.SEND_SMS_STATUS.STANDBY_STATUS
        private var threadMain: Thread? = null
        private var firstRequest = true
        private fun isNumeric(str: String?): Boolean {
            for (i in 0 until str!!.length) {
                println(str[i])
                if (!Character.isDigit(str[i])) {
                    return false
                }
            }
            return true
        }
    }
}
