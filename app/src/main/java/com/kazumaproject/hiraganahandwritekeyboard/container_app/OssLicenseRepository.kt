package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.content.Context
import org.json.JSONArray
import java.nio.charset.Charset

object OssLicenseRepository {

    fun loadFromAssets(
        context: Context,
        assetFileName: String = "oss_licenses.json"
    ): List<OssLicense> {
        val json = context.assets.open(assetFileName).use { input ->
            input.readBytes().toString(Charset.forName("UTF-8"))
        }

        val arr = JSONArray(json)
        val out = ArrayList<OssLicense>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val artifact = o.optString("artifact").trim()
            val licenseName = o.optString("licenseName").trim()
            val licenseUrl = o.optString("licenseUrl").trim().ifEmpty { null }
            val licenseText = o.optString("licenseText")

            if (name.isNotEmpty()) {
                out.add(
                    OssLicense(
                        name = name,
                        artifact = artifact,
                        licenseName = licenseName,
                        licenseUrl = licenseUrl,
                        licenseText = licenseText
                    )
                )
            }
        }

        // 見やすさのため名前順
        return out.sortedBy { it.name.lowercase() }
    }
}
