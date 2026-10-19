package com.unrealstar.live

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.api.SessionStore
import kotlinx.coroutines.launch

/**
 * Minimal OTP login (closes the "no sign-in screen" gap).
 * Step 1: phone → POST /auth/otp/send. Step 2: code → POST /auth/otp/verify,
 * tokens persisted via SessionStore. Debug builds show the debug_otp code.
 */
class OtpLoginActivity : AppCompatActivity() {

    private var otpSessionId: String? = null
    private var lastSendMs: Long = 0L
    private var verifyAttempts: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)

        val phoneInput = EditText(this).apply {
            hint = getString(R.string.otp_phone_hint)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val sendBtn = Button(this).apply { text = getString(R.string.send_otp) }
        val codeInput = EditText(this).apply {
            hint = getString(R.string.otp_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setAutofillHints("smsOTP")
        }
        val verifyBtn = Button(this).apply { text = getString(R.string.verify_sign_in) }
        val status = TextView(this)

        sendBtn.setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            // E.164 sanity: optional +, 7–15 digits. Anything else wastes
            // SMS budget and can split identity server-side.
            if (!phone.matches(Regex("^\\+?[0-9]{7,15}$"))) {
                status.text = getString(R.string.otp_bad_phone)
                return@setOnClickListener
            }
            // Resend cooldown: 30s between sends from this screen.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSendMs < 30_000) {
                status.text = getString(R.string.otp_resend_wait)
                return@setOnClickListener
            }
            lastSendMs = now
            lifecycleScope.launch {
                val res = UnrealStarApiClient.otpSend(phone)
                res.onSuccess {
                    otpSessionId = it.otp_session_id
                    verifyAttempts = 0
                    // Debug OTP is a dev-backend affordance: show it only in
                    // debug builds, never render server codes in release.
                    status.text = if (com.unrealstar.live.BuildConfig.DEBUG && it.debug_otp != null) {
                        getString(R.string.otp_debug_code, it.debug_otp)
                    } else {
                        getString(R.string.otp_code_sent)
                    }
                }.onFailure {
                    status.text = getString(R.string.otp_send_failed, it.message)
                }
            }
        }
        verifyBtn.setOnClickListener {
            val sid = otpSessionId
            if (sid.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.otp_send_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val code = codeInput.text.toString().trim()
                if (!code.matches(Regex("^[0-9]{6}$"))) {
                    status.text = getString(R.string.otp_need_six)
                    return@launch
                }
                verifyAttempts++
                if (verifyAttempts > 5) {
                    status.text = getString(R.string.otp_too_many)
                    otpSessionId = null
                    verifyAttempts = 0
                    return@launch
                }
                val res = UnrealStarApiClient.otpVerify(
                    sid, code, SessionStore.deviceUid(this@OtpLoginActivity)
                )
                res.onSuccess { tokens ->
                    SessionStore.signIn(
                        this@OtpLoginActivity,
                        tokens.access_token,
                        tokens.refresh_token,
                        tokens.user_id
                    )
                    Toast.makeText(this@OtpLoginActivity, getString(R.string.otp_signed_in), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure {
                    status.text = getString(R.string.otp_verify_failed, it.message)
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(phoneInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sendBtn)
            addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(verifyBtn)
            addView(status)
        }
        setContentView(root)
    }
}
