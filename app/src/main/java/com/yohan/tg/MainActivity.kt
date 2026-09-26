package com.yohan.tg

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.vanta.memberadder.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var step = 0
    private var apiId: Int = 0
    private var apiHash: String = ""
    private val accounts = mutableListOf<String>() // session names
    private var currentSession: String = "default"

    private val py by lazy { Python.getInstance() }
    private val engine by lazy { py.getModule("engine") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val speeds = arrayOf("بطيء (1)", "متوسط (5)", "سريع (10)", "سريع جداً (20)")
        binding.spinnerSpeed.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speeds)
        binding.spinnerSpeed.setSelection(2)

        binding.btnAction.setOnClickListener { onAction() }
        binding.btnAddAccount.setOnClickListener {
            step = 2
            currentSession = "acc_${System.currentTimeMillis()}"
            showStep()
            log("إضافة حساب جديد...")
        }

        showStep()
    }

    private fun showStep() {
        binding.tilApiId.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.tilApiHash.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.tilPhone.visibility = if (step == 2) View.VISIBLE else View.GONE
        binding.tilCode.visibility = if (step == 3) View.VISIBLE else View.GONE
        binding.tilPassword.visibility = if (step == 4) View.VISIBLE else View.GONE
        binding.tilSource.visibility = if (step == 5) View.VISIBLE else View.GONE
        binding.tilTarget.visibility = if (step == 5) View.VISIBLE else View.GONE
        binding.tvSpeedLabel.visibility = if (step == 5) View.VISIBLE else View.GONE
        binding.spinnerSpeed.visibility = if (step == 5) View.VISIBLE else View.GONE
        binding.btnAddAccount.visibility = if (step >= 5) View.VISIBLE else View.GONE

        binding.tvStatus.text = when (step) {
            0 -> "أدخل API ID من my.telegram.org"
            1 -> "أدخل API Hash"
            2 -> "أدخل رقم الهاتف بالصيغة الدولية"
            3 -> "أدخل كود التحقق الذي وصل"
            4 -> "أدخل كلمة مرور التحقق بخطوتين"
            5 -> "أدخل روابط المجموعات وابدأ"
            else -> "جاهز"
        }

        binding.btnAction.text = when (step) {
            0, 1 -> "التالي"
            2 -> "إرسال الكود"
            3, 4 -> "تسجيل الدخول"
            5 -> "بدء الإضافة"
            else -> "التالي"
        }

        updateAccountsText()
    }

    private fun onAction() {
        when (step) {
            0 -> {
                val id = binding.etApiId.text.toString().trim()
                if (id.isEmpty()) {
                    toast("أدخل API ID")
                    return
                }
                apiId = id.toIntOrNull() ?: run {
                    toast("API ID غير صالح")
                    return
                }
                step = 1
                showStep()
            }
            1 -> {
                apiHash = binding.etApiHash.text.toString().trim()
                if (apiHash.isEmpty()) {
                    toast("أدخل API Hash")
                    return
                }
                step = 2
                showStep()
            }
            2 -> {
                val phone = binding.etPhone.text.toString().trim()
                if (phone.isEmpty()) {
                    toast("أدخل رقم الهاتف")
                    return
                }
                sendCode(phone)
            }
            3 -> {
                val code = binding.etCode.text.toString().trim()
                if (code.isEmpty()) {
                    toast("أدخل الكود")
                    return
                }
                signIn(code)
            }
            4 -> {
                val password = binding.etPassword.text.toString()
                if (password.isEmpty()) {
                    toast("أدخل كلمة المرور")
                    return
                }
                checkPassword(password)
            }
            5 -> {
                val source = binding.etSource.text.toString().trim()
                val target = binding.etTarget.text.toString().trim()
                if (source.isEmpty() || target.isEmpty()) {
                    toast("أدخل روابط المجموعتين")
                    return
                }
                val speed = when (binding.spinnerSpeed.selectedItemPosition) {
                    0 -> 1
                    1 -> 5
                    2 -> 10
                    else -> 20
                }
                startAdding(source, target, speed)
            }
        }
    }

    private fun sendCode(phone: String) {
        binding.btnAction.isEnabled = false
        log("جاري إرسال كود التحقق...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.callAttr(
                    "send_code",
                    apiId,
                    apiHash,
                    phone,
                    currentSession,
                    filesDir.absolutePath
                ).toString()
            }
            binding.btnAction.isEnabled = true
            handleResult(result) {
                step = 3
                showStep()
                log("تم إرسال الكود. أدخله الآن.")
            }
        }
    }

    private fun signIn(code: String) {
        binding.btnAction.isEnabled = false
        log("جاري تسجيل الدخول...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.callAttr(
                    "sign_in",
                    currentSession,
                    filesDir.absolutePath,
                    code
                ).toString()
            }
            binding.btnAction.isEnabled = true
            val json = JSONObject(result)
            when (json.optString("status")) {
                "ok" -> {
                    accounts.add(currentSession)
                    step = 5
                    showStep()
                    log("تم تسجيل الدخول بنجاح: ${json.optString("name")}")
                }
                "2fa" -> {
                    step = 4
                    showStep()
                    log("مطلوب كلمة مرور التحقق بخطوتين")
                }
                else -> log("خطأ: ${json.optString("error")}")
            }
        }
    }

    private fun checkPassword(password: String) {
        binding.btnAction.isEnabled = false
        log("جاري التحقق من كلمة المرور...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.callAttr(
                    "check_password",
                    currentSession,
                    filesDir.absolutePath,
                    password
                ).toString()
            }
            binding.btnAction.isEnabled = true
            val json = JSONObject(result)
            if (json.optString("status") == "ok") {
                accounts.add(currentSession)
                step = 5
                showStep()
                log("تم تسجيل الدخول بنجاح: ${json.optString("name")}")
            } else {
                log("خطأ: ${json.optString("error")}")
            }
        }
    }

    private fun startAdding(source: String, target: String, speed: Int) {
        if (accounts.isEmpty()) {
            toast("أضف حساباً واحداً على الأقل")
            return
        }
        binding.btnAction.isEnabled = false
        log("بدء عملية الجمع والإضافة (سرعة: $speed)...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.callAttr(
                    "run_adder",
                    apiId,
                    apiHash,
                    accounts.toTypedArray(),
                    filesDir.absolutePath,
                    source,
                    target,
                    speed
                ).toString()
            }
            binding.btnAction.isEnabled = true
            val json = JSONObject(result)
            log(json.optString("log", result))
            if (json.optString("status") == "ok") {
                log("انتهى — نجح: ${json.optInt("added")} | فشل: ${json.optInt("failed")}")
            } else {
                log("خطأ: ${json.optString("error")}")
            }
        }
    }

    private fun handleResult(raw: String, onOk: () -> Unit) {
        try {
            val json = JSONObject(raw)
            if (json.optString("status") == "ok") {
                onOk()
            } else {
                log("خطأ: ${json.optString("error")}")
            }
        } catch (e: Exception) {
            log("خطأ: $raw")
        }
    }

    private fun updateAccountsText() {
        binding.tvAccounts.text = if (accounts.isEmpty()) {
            "لا توجد حسابات مسجلة"
        } else {
            "الحسابات (${accounts.size}): ${accounts.joinToString(", ")}"
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            binding.tvLog.append("$msg\n")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}