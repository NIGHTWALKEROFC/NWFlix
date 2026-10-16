package app.nwmovie

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.nwmovie.data.K
import app.nwmovie.ui.LockScreen
import app.nwmovie.ui.NWRoot
import app.nwmovie.ui.theme.NWTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (savedInstanceState == null) AppState.locked.value = true   // fresh launch → ask for unlock (if lock is on)

        setContent {
            val p by ServiceLocator.prefs.collectAsState()
            val locked by AppState.locked.collectAsState()
            LaunchedEffect(p.blockShots) {
                if (p.blockShots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            NWTheme(p) {
                NWRoot(vm)
                if (p.appLock && locked) LockScreen(onUnlock = { authenticate() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ServiceLocator.scope.launch { ServiceLocator.settings.set(K.lastOpen, System.currentTimeMillis()) }
        vm.scanIfStale()   // auto-scan for new movies every time the app is opened
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { AppState.locked.value = true; vm.stopScan() }
    }

    private fun authenticate() {
        val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            AppState.locked.value = false   // no screen lock set up on this phone, nothing to check against
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { AppState.locked.value = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock NWMovie")
                .setSubtitle("Use fingerprint, face or your phone PIN").setAllowedAuthenticators(allowed).build()
        )
    }

    // ----- Picture-in-Picture -----
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AppState.playerOpen && AppState.playerPlaying) enterPip()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            try { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AppState.inPip.value = isInPictureInPictureMode
    }
}
