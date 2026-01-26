package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kazumaproject.hiraganahandwritekeyboard.databinding.ActivityLicenseDetailBinding

class LicenseDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LICENSE = "extra_license"
    }

    private lateinit var binding: ActivityLicenseDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge は維持
        enableEdgeToEdge()

        binding = ActivityLicenseDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ここが重要：システムバー（ステータスバー/ナビゲーションバー/カットアウト）の分だけ余白を足す
        applySystemBarsInsetsPadding(binding.root)

        val item = intent.getParcelableExtra<OssLicense>(EXTRA_LICENSE)
        if (item == null) {
            finish()
            return
        }

        binding.nameText.text = item.name

        val metaParts = buildList {
            if (item.artifact.isNotBlank()) add(item.artifact)
            if (item.licenseName.isNotBlank()) add(item.licenseName)
            if (!item.licenseUrl.isNullOrBlank()) add(item.licenseUrl!!)
        }
        binding.metaText.text = metaParts.joinToString(" • ")

        binding.licenseText.text = item.licenseText.ifBlank { "-" }

        val url = item.licenseUrl?.trim().orEmpty()
        if (url.isBlank()) {
            binding.openUrlButton.visibility = View.GONE
        } else {
            binding.openUrlButton.visibility = View.VISIBLE
            binding.openUrlButton.setOnClickListener {
                openBrowser(url)
            }
        }
    }

    private fun applySystemBarsInsetsPadding(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }

        // すぐ適用（画面回転/再描画でも再計算される）
        ViewCompat.requestApplyInsets(view)
    }

    private fun openBrowser(url: String) {
        // 最低限の補正：スキーム無しなら https:// を付ける
        val normalized = if (
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            url
        } else {
            "https://$url"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // ブラウザが無いなど。クラッシュさせない。
        } catch (_: Exception) {
            // URI 不正など。クラッシュさせない。
        }
    }
}
