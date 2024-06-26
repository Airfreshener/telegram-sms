package com.airfreshener.telegram_sms.mainScreen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.airfreshener.telegram_sms.utils.ContextUtils.app

class MainViewModelFactory(
    private val appContext: Context
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = appContext.app()
        return MainViewModel(
            appContext = appContext,
            prefsRepository = app.prefsRepository,
            logRepository = app.logRepository,
        ) as T
    }
}
