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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)

        val phoneInput = EditText(this).apply { hint = "Phone (e.g. +919876543210)" }
        val sendBtn = Button(this).apply { text = "Send OTP" }
        val codeInput = EditText(this).apply {
            hint = "6-digit code"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val verifyBtn = Button(this).apply { text = "Verify & Sign In" }
        val status = TextView(this)

        sendBtn.setOnClickListener {
            lifecycleScope.launch {
                val res = UnrealStarApiClient.otpSend(phoneInput.text.toString().trim())
                res.onSuccess {
                    otpSessionId = it.otp_session_id
                    status.text = if (it.debug_otp != null) "Debug code: ${it.debug_otp}" else "Code sent"
                }.onFailure {
                    status.text = "Send failed: ${it.message}"
                }
            }
        }
        verifyBtn.setOnClickListener {
            val sid = otpSessionId
            if (sid.isNullOrBlank()) {
                Toast.makeText(this, "Send OTP first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val res = UnrealStarApiClient.otpVerify(sid, codeInput.text.toString().trim())
                res.onSuccess { tokens ->
                    SessionStore.signIn(this@OtpLoginActivity, tokens.access_token, tokens.user_id)
                    Toast.makeText(this@OtpLoginActivity, "Signed in as ${tokens.user_id}", Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure {
                    status.text = "Verify failed: ${it.message}"
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
