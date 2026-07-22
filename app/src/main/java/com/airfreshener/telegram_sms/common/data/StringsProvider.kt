package com.airfreshener.telegram_sms.common.data

import android.content.Context

class StringsProvider(private val context: Context) {
    fun getString(resId: Int): String = context.getString(resId)
}
