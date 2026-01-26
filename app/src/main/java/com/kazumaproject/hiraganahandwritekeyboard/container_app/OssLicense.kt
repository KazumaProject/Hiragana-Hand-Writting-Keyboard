package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class OssLicense(
    val name: String,
    val artifact: String,
    val licenseName: String,
    val licenseUrl: String?,
    val licenseText: String
) : Parcelable
