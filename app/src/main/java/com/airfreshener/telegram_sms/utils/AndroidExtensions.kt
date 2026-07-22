package com.airfreshener.telegram_sms.utils

import android.Manifest
import android.Manifest.permission.READ_PHONE_STATE
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

fun ContextWrapper.isCameraPermissionGranted(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

fun Activity.requestReadPhoneStatePermission(requestCode: Int) =
    ActivityCompat.requestPermissions(this, arrayOf(READ_PHONE_STATE), requestCode)

fun Context.isReadPhoneStatePermissionGranted() =
    ContextCompat.checkSelfPermission(this, READ_PHONE_STATE) == PERMISSION_GRANTED
