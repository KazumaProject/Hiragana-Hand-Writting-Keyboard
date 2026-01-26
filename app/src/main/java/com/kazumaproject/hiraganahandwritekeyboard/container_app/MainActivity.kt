package com.kazumaproject.hiraganahandwritekeyboard.container_app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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

        binding.openEnableImeSettingsButton.setOnClickListener {
            openEnableImeSettings()
        }

        binding.showImePickerButton.setOnClickListener {
            showImePicker()
        }

        // 初回表示
        renderImeStatus()
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻ってきたときに反映
        renderImeStatus()
    }

    private fun renderImeStatus() {
        val imeId = imeId()
        val enabled = isImeEnabled(imeId)
        val selected = isImeSelected(imeId)
        // 表示制御：
        // - Enabledでない => 「有効化へ」ボタンを出す
        // - EnabledだがSelectedでない => 「切り替え」ボタンを出す（IMEピッカー）
        // - 両方OK => どちらも隠す
        binding.openEnableImeSettingsButton.visibility =
            if (!enabled) android.view.View.VISIBLE else android.view.View.GONE

        binding.showImePickerButton.visibility =
            if (enabled && !selected) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * このアプリ内サービスIMEのID（Settingsに出る形式）
     * 例: com.example.app/com.example.app.input_method.HiraganaImeService
     */
    private fun imeId(): String {
        val cn =
            "${packageName}/com.kazumaproject.hiraganahandwritekeyboard.input_method.HiraganaImeService"
        return cn
    }

    /**
     * IMEが「有効化」されているか（Enabled Input Methods）
     */
    private fun isImeEnabled(imeId: String): Boolean {
        val enabledIds = getEnabledImeIds(this)
        return enabledIds.contains(imeId)
    }

    /**
     * IMEが「現在選択されているか」（Default/Input Method）
     */
    private fun isImeSelected(imeId: String): Boolean {
        val current =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return current == imeId
    }

    private fun getEnabledImeIds(context: Context): Set<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // enabledInputMethodList は現在有効なIME一覧（InputMethodInfoのidが設定用文字列）
        return imm.enabledInputMethodList.map { it.id }.toSet()
    }

    /**
     * 設定の「仮想キーボード（管理）」など、IMEを有効化する画面へ
     * 端末メーカー差はあるが、ここが最も標準的。
     */
    private fun openEnableImeSettings() {
        // ACTION_INPUT_METHOD_SETTINGS: 「キーボード」設定画面
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * IME切り替え（選択）を促すなら、設定へ飛ばすよりこれが確実
     * ※フォーカスが必要な場合があるので、About画面に入力欄が無いと出ない端末もある。
     * その場合は Settings.ACTION_INPUT_METHOD_SETTINGS を使う。
     */
    private fun showImePicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
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
