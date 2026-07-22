package com.airfreshener.telegram_sms.utils

import android.util.Log
import com.airfreshener.telegram_sms.common.data.LogRepository

class LoggerImpl(
    private val logRepository: LogRepository
) : Logger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
        logRepository.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        logRepository.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
        logRepository.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
        logRepository.e(tag, message, throwable)
    }
}
