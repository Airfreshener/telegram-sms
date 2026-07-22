package com.airfreshener.telegram_sms.common.data

import kotlinx.coroutines.flow.StateFlow

interface LogRepository {

    val logs: StateFlow<List<String>>

    fun writeLog(log: String)
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    fun resetLogFile()
}
