package com.keithvassallo.ncmediaprovider.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.databinding.ActivitySignInBinding

/**
 * Signing in on its own screen (#26), for signing in again after the server refused the app's
 * password; first sign-in happens in onboarding. The flow itself is [LoginFlowController].
 */
class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding
    private val repository by lazy { LibraryRepository.get(applicationContext) }
    private val login = LoginFlowController(this) { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) repository.account()?.let { binding.serverField.setText(it.baseUrl) }
        binding.continueButton.setOnClickListener { login.start(binding.serverField.text?.toString().orEmpty()) }
        binding.serverField.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_GO).also { if (it) login.start(binding.serverField.text?.toString().orEmpty()) }
        }
        binding.reopenButton.setOnClickListener { login.reopen() }
        login.restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        login.save(outState)
    }

    private fun render(state: LoginFlowController.State) {
        binding.httpWarning.visibility = if (login.plainHttp) View.VISIBLE else View.GONE
        when (state) {
            LoginFlowController.State.Idle -> showIdle("")
            is LoginFlowController.State.Busy -> {
                binding.status.text = state.message
                binding.progress.visibility = View.VISIBLE
                binding.continueButton.isEnabled = false
                binding.reopenButton.visibility = View.GONE
            }
            LoginFlowController.State.Waiting -> {
                binding.status.setText(R.string.sign_in_waiting)
                binding.progress.visibility = View.VISIBLE
                binding.continueButton.isEnabled = true
                binding.reopenButton.visibility = View.VISIBLE
            }
            is LoginFlowController.State.Failed -> {
                showIdle(state.message)
                binding.reopenButton.visibility = View.GONE
            }
            LoginFlowController.State.SignedIn -> {
                if (!repository.foldersChosen) startActivity(Intent(this, FolderPickerActivity::class.java))
                finish()
            }
        }
    }

    private fun showIdle(message: String) {
        binding.status.text = message
        binding.progress.visibility = View.GONE
        binding.continueButton.isEnabled = true
    }
}
