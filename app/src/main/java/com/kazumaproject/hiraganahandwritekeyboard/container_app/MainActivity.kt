package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.kazumaproject.hiraganahandwritekeyboard.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.versionText.text = buildVersionText()

        val licenses = OssLicenseRepository.loadFromAssets(this)

        binding.licensesRecycler.layoutManager = LinearLayoutManager(this)
        binding.licensesRecycler.adapter = OssLicenseAdapter(licenses) { item ->
            val itn = Intent(this, LicenseDetailActivity::class.java)
            itn.putExtra(LicenseDetailActivity.EXTRA_LICENSE, item)
            startActivity(itn)
        }
    }

    private fun buildVersionText(): String {
        val pm = packageManager
        val pkg = packageName

        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }

        val versionName = info.versionName ?: "-"
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        return "Version: $versionName ($versionCode)"
    }
}
