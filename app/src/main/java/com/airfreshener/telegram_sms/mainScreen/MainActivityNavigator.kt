package com.airfreshener.telegram_sms.mainScreen

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.airfreshener.telegram_sms.R
import com.airfreshener.telegram_sms.common.data.PrefsRepository
import com.airfreshener.telegram_sms.databinding.SetProxyLayoutBinding
import com.airfreshener.telegram_sms.logScreen.LogcatActivity
import com.airfreshener.telegram_sms.spamListScreen.SpamListActivity
import com.airfreshener.telegram_sms.utils.PaperUtils
import com.airfreshener.telegram_sms.utils.PaperUtils.SYSTEM_BOOK
import com.airfreshener.telegram_sms.utils.ServiceUtils
import androidx.core.net.toUri

class MainActivityNavigator(
    private val activity: Activity,
    private val prefsRepository: PrefsRepository,
) {

    private val qaUrl: String
        get() = "$WEB_VIEW_PAGES_URL/${activity.getString(R.string.Lang)}/Q&A"
    private val manualUrl: String
        get() = "$WEB_VIEW_PAGES_URL/${activity.getString(R.string.Lang)}/user-manual"
    private val privacyPolicy: String
        get() = "$WEB_VIEW_PAGES_URL/${activity.getString(R.string.Lang)}/privacy-policy"

    fun showPrivacyDialog(
        onPositiveClick: () -> Unit,
        onNeutralClick:() -> Unit,
    ) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.privacy_reminder_title)
        builder.setMessage(R.string.privacy_reminder_information)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.agree) { _: DialogInterface?, _: Int ->
            onPositiveClick()
        }
        builder.setNeutralButton(R.string.visit_page) { _: DialogInterface?, _: Int ->
            onNeutralClick()
        }
        builder.create().apply {
            getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
            getButton(AlertDialog.BUTTON_NEUTRAL)?.isAllCaps = false
        }.show()
    }

    fun showManual(onFailure: () -> Unit) {
        showWebView(manualUrl.toUri(), onFailure)
    }
    fun showPrivacyPolicy(onFailure: () -> Unit) {
        showWebView(privacyPolicy.toUri(), onFailure)
    }

    fun showQA(onFailure: () -> Unit) {
        showWebView(qaUrl.toUri(), onFailure)
    }
    fun showWebView(uri: Uri, onFailure: () -> Unit) {
        val privacyBuilder = CustomTabsIntent.Builder()
        privacyBuilder.setToolbarColor(
            ContextCompat.getColor(activity, R.color.colorPrimary)
        )
        val customTabsIntent = privacyBuilder.build()
        customTabsIntent.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTabsIntent.launchUrl(activity, uri)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            onFailure()
        }
    }

    fun showLogsScreen() {
        val logcatIntent = Intent(activity, LogcatActivity::class.java)
        activity.startActivity(logcatIntent)
    }

    fun showSpamList() {
        activity.startActivity(Intent(activity, SpamListActivity::class.java))
    }

    fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    fun showAboutScreen() {
        val appContext = activity.applicationContext
        val packageManager = appContext.packageManager
        val versionName = try {
            packageManager.getPackageInfo(appContext.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            "unknown"
        }
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.about_title)
        builder.setMessage(activity.getString(R.string.about_content) + versionName)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.ok_button, null)
        builder.show()
    }

    fun showProxySettingsDialog(
        onOkCallback: (isChecked: Boolean) -> Unit
    ) {
        val appContext = activity.applicationContext
        val proxyItem = PaperUtils.getProxyConfig()
        val proxyDialogView = activity.layoutInflater.inflate(R.layout.set_proxy_layout, null)
        val binding = SetProxyLayoutBinding.bind(proxyDialogView)
        binding.proxyEnableSwitch.isChecked = proxyItem.enable
        binding.dohOverSocks5Switch.isChecked = proxyItem.dns_over_socks5
        binding.proxyHostEditview.setText(proxyItem.host)
        binding.proxyPortEditview.setText(proxyItem.port.toString())
        binding.proxyUsernameEditview.setText(proxyItem.username)
        binding.proxyPasswordEditview.setText(proxyItem.password)
        AlertDialog.Builder(activity).setTitle(R.string.proxy_dialog_title)
            .setView(proxyDialogView)
            .setPositiveButton(R.string.ok_button) { _: DialogInterface?, _: Int ->
                onOkCallback(binding.proxyEnableSwitch.isChecked)
                proxyItem.enable = binding.proxyEnableSwitch.isChecked
                proxyItem.dns_over_socks5 = binding.dohOverSocks5Switch.isChecked
                proxyItem.host = binding.proxyHostEditview.text.toString()
                proxyItem.port = binding.proxyPortEditview.text.toString().toInt()
                proxyItem.username = binding.proxyUsernameEditview.text.toString()
                proxyItem.password = binding.proxyPasswordEditview.text.toString()
                SYSTEM_BOOK.write("proxy_config", proxyItem)
                Thread {
                    ServiceUtils.stopAllServices(appContext)
                    if (prefsRepository.getInitialized()) {
                        ServiceUtils.startServices(appContext, prefsRepository.getSettings())
                    }
                }.start()
            }
            .show()
    }

    companion object {
        private const val CAMERA_PERMISSION_CODE = 0
        private const val WEB_VIEW_PAGES_URL = "https://get.telegram-sms.com/guide"
    }
}
