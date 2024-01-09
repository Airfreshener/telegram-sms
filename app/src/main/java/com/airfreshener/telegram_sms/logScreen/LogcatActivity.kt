package com.airfreshener.telegram_sms.logScreen

import android.os.Bundle
import android.os.FileObserver
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airfreshener.telegram_sms.R
import com.airfreshener.telegram_sms.utils.ContextUtils.app

class LogcatActivity : AppCompatActivity(R.layout.activity_logcat) {

    private var observer: LogcatFileObserver? = null
    private var logcatTextView: TextView? = null
    private val logRepository by lazy { app().logRepository }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val logcatTextView = findViewById<TextView>(R.id.logcat_textview).apply { logcatTextView = this }
        this.setTitle(R.string.logcat)
        logcatTextView.text = logRepository.readLog(LINES_COUNT)
        observer = LogcatFileObserver(applicationContext.filesDir.absolutePath, logcatTextView)
    }

    public override fun onPause() {
        super.onPause()
        observer?.stopWatching()
    }

    public override fun onResume() {
        super.onResume()
        logcatTextView?.text = logRepository.readLog(LINES_COUNT)
        observer?.startWatching()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.logcat_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        logRepository.resetLogFile()
        return true
    }

    private inner class LogcatFileObserver(
        path: String,
        private val logcat: TextView
    ) : FileObserver(path) {
        override fun onEvent(event: Int, path: String?) {
            if (event == MODIFY && path?.contains("error.log") == true) {
                runOnUiThread { logcat.text = logRepository.readLog(LINES_COUNT) }
            }
        }
    }

    companion object {
        private const val LINES_COUNT = 100
    }
}
