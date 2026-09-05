package com.lateropulsion.app.ui.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lateropulsion.app.R
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.auth.AuthState
import com.lateropulsion.app.ui.components.BigButton
import com.lateropulsion.app.ui.components.LpTextField
import com.lateropulsion.app.ui.components.Selector
import com.lateropulsion.core.common.fold
import com.lateropulsion.core.model.Clinician
import com.lateropulsion.core.model.ClinicianRole
import com.lateropulsion.core.model.SecurityConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(val auth: AuthManager, val security: SecurityConfig) : ViewModel() {
    val error = MutableStateFlow<String?>(null)

    fun create(name: String, role: ClinicianRole, pin: String, confirm: String) {
        if (pin != confirm) { error.value = "PINs do not match"; return }
        viewModelScope.launch { auth.createAccount(name, role, pin).fold({ error.value = null }, { error.value = it.message }) }
    }

    fun unlock(c: Clinician, pin: String) { viewModelScope.launch { auth.unlock(c.id, pin).fold({ error.value = null }, { error.value = it.message }) } }
    fun biometric(c: Clinician) { viewModelScope.launch { auth.unlockWithBiometric(c.id) } }
}

@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
    val state by vm.auth.state.collectAsState()
    val error by vm.error.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(R.string.not_certified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        when (val s = state) {
            AuthState.NoAccount -> CreateAccount(vm, error)
            is AuthState.Locked -> Unlock(vm, s, error)
            else -> Unit
        }
    }
}

@Composable
private fun CreateAccount(vm: AuthViewModel, error: String?) {
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(ClinicianRole.PHYSIOTHERAPIST) }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.create_account_title), style = MaterialTheme.typography.headlineMedium)
    LpTextField(name, { name = it }, stringResource(R.string.display_name))
    Selector(stringResource(R.string.role), ClinicianRole.entries, role, { it.name.lowercase().replace('_', ' ') }, { role = it })
    LpTextField(pin, { pin = it.filter(Char::isDigit) }, stringResource(R.string.pin, vm.security.pinMinLength), password = true)
    LpTextField(confirm, { confirm = it.filter(Char::isDigit) }, stringResource(R.string.pin_confirm), password = true, error = error)
    BigButton(stringResource(R.string.create_account), { vm.create(name, role, pin, confirm) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && pin.length >= vm.security.pinMinLength)
}

@Composable
private fun Unlock(vm: AuthViewModel, s: AuthState.Locked, error: String?) {
    var selected by rememberSaveable { mutableStateOf(s.clinicians.firstOrNull()?.id?.value) }
    var pin by rememberSaveable { mutableStateOf("") }
    val ctx = LocalContext.current
    val clinician = s.clinicians.firstOrNull { it.id.value == selected }
    Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineMedium)
    s.message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    Selector(stringResource(R.string.select_clinician), s.clinicians, clinician, { it.displayName }, { selected = it.id.value })
    LpTextField(pin, { pin = it.filter(Char::isDigit) }, "PIN", password = true, error = error)
    BigButton(stringResource(R.string.unlock), { clinician?.let { vm.unlock(it, pin); pin = "" } }, Modifier.fillMaxWidth(), enabled = clinician != null && pin.isNotEmpty())
    val canBio = BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    if (canBio && clinician != null) {
        BigButton(stringResource(R.string.use_biometric), {
            val activity = ctx as? FragmentActivity ?: return@BigButton
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.biometric(clinician) }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Lateropulsion").setSubtitle(clinician.displayName)
                .setNegativeButtonText("Use PIN").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
            prompt.authenticate(info)
        }, Modifier.fillMaxWidth(), secondary = true)
    }
}
