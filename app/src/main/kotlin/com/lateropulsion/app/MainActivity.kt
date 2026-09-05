package com.lateropulsion.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lateropulsion.app.auth.AuthManager
import com.lateropulsion.app.di.AuthBinder
import com.lateropulsion.app.ui.nav.LpApp
import com.lateropulsion.app.ui.theme.LpTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity clinician UI. FragmentActivity because BiometricPrompt needs one.
 * FLAG_SECURE on the window: no screenshots, no recents thumbnail (REQ-SEC-003).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var auth: AuthManager
    @Inject lateinit var binder: AuthBinder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        lifecycleScope.launch { binder.bind() }
        lifecycleScope.launch { auth.refresh() }
        // Auto-lock: on background, and on an idle heartbeat while in the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { lifecycleScope.launch { auth.autoLockIfIdle(inBackground = true) } }
        })
        lifecycleScope.launch { while (true) { delay(15_000); auth.autoLockIfIdle(inBackground = false) } }
        setContent { LpTheme { LpApp(onUserInteraction = { auth.touch() }) } }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        auth.touch()
    }
}
