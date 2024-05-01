package com.stopstone

import android.Manifest

object Constants {
    const val TAG = "cameraX"
    const val RATIO_4_3_VALUE: Double = 4.0 / 3.0
    const val RATIO_16_9_VALUE: Double = 16.0 / 9.0
    const val REQUEST_CODE_PERMISSIONS = 0x98
    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(Manifest.permission.CAMERA)

}