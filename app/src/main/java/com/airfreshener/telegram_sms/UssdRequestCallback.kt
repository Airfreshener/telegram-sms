package com.airfreshener.telegram_sms

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.UssdResponseCallback
import androidx.annotation.RequiresApi
import com.airfreshener.telegram_sms.model.RequestMessage
import com.airfreshener.telegram_sms.utils.LogUtils
import com.airfreshener.telegram_sms.utils.NetworkUtils.getOkhttpObj
import com.airfreshener.telegram_sms.utils.NetworkUtils.getUrl
import com.airfreshener.telegram_sms.utils.OkHttpUtils.toRequestBody
import com.airfreshener.telegram_sms.utils.PaperUtils.getProxyConfig
import com.airfreshener.telegram_sms.utils.PaperUtils.init
import com.airfreshener.telegram_sms.utils.ResendUtils.addResendLoop
import com.airfreshener.telegram_sms.utils.SmsUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

@RequiresApi(api = Build.VERSION_CODES.O)
class UssdRequestCallback(
    private val context: Context,
    sharedPreferences: SharedPreferences,
    messageId: Long
) : UssdResponseCallback() {
    private val dohSwitch: Boolean
    private var requestUri: String
    private val messageHeader: String
    private val requestBody: RequestMessage

    init {
        init(context)
        val chatId = sharedPreferences.getString("chat_id", "")
        dohSwitch = sharedPreferences.getBoolean("doh_switch", true)
        requestBody = RequestMessage()
        requestBody.chat_id = chatId
        val botToken = sharedPreferences.getString("bot_token", "")
        requestUri = getUrl(botToken!!, "SendMessage")
        if (messageId != -1L) {
            requestUri = getUrl(botToken, "editMessageText")
            requestBody.message_id = messageId
        }
        messageHeader = context.getString(R.string.send_ussd_head)
    }

    override fun onReceiveUssdResponse(
        telephonyManager: TelephonyManager,
        request: String,
        response: CharSequence
    ) {
        super.onReceiveUssdResponse(telephonyManager, request, response)
        val message = """
            $messageHeader
            ${context.getString(R.string.request)}$request
            ${context.getString(R.string.content)}$response
            """.trimIndent()
        networkProgressHandle(message)
    }

    override fun onReceiveUssdResponseFailed(
        telephonyManager: TelephonyManager,
        request: String,
        failureCode: Int
    ) {
        super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
        val message = """
            $messageHeader
            ${context.getString(R.string.request)}$request
            ${context.getString(R.string.error_message)}${getErrorCodeString(failureCode)}
            """.trimIndent()
        networkProgressHandle(message)
    }

    private fun networkProgressHandle(message: String) {
        requestBody.text = message
        val body = requestBody.toRequestBody()
        val okHttpClient = getOkhttpObj(dohSwitch, getProxyConfig())
        val requestObj: Request = Request.Builder().url(requestUri).method("POST", body).build()
        val call = okHttpClient.newCall(requestObj)
        val errorHead = "Send USSD failed:"
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                LogUtils.writeLog(context, errorHead + e.message)
                SmsUtils.sendFallbackSms(context, requestBody.text, -1)
                addResendLoop(context, requestBody.text)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code != 200) {
                    val responseBody = response.body ?: return
                    LogUtils.writeLog(
                        context,
                        errorHead + response.code + " " + responseBody.string()
                    )
                    SmsUtils.sendFallbackSms(context, requestBody.text, -1)
                    addResendLoop(context, requestBody.text)
                }
            }
        })
    }

    private fun getErrorCodeString(errorCode: Int): String {
        val result: String = when (errorCode) {
            -1 -> "Connection problem or invalid MMI code."
            -2 -> "No service."
            else -> "An unknown error occurred ($errorCode)"
        }
        return result
    }
}