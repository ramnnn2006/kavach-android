package app.kavach.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kavach.android.core.data.net.ConnectivityObserver
import app.kavach.android.core.nav.DeepLink
import app.kavach.android.core.nav.DeepLinks
import app.kavach.android.core.nav.KavachRoot
import app.kavach.android.core.nav.LinkTarget
import app.kavach.android.core.nav.RootUi
import app.kavach.android.core.nav.RootViewModel
import app.kavach.android.core.ui.components.LocalIsOnline
import app.kavach.android.core.ui.components.LocalReduceMotion
import app.kavach.android.core.ui.components.reduceMotionSetting
import app.kavach.android.core.ui.theme.KavachTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var connectivity: ConnectivityObserver
    private val root: RootViewModel by viewModels()

    /** System "Remove animations", re-read on every resume so a change applies right away. */
    private var reduceMotion by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the system splash while a saved session is restored
        splash.setKeepOnScreenCondition { root.ui.value == RootUi.Loading }
        enableEdgeToEdge()
        reduceMotion = reduceMotionSetting()

        // A recreated activity, or one reopened from Recents (which replays the original
        // notification intent), must not open the old link again
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        val link = if (fresh) DeepLinks.parse(intent) else null
        showOverLockScreen(link)
        root.onDeepLink(link)

        setContent {
            val online by connectivity.isOnline.collectAsStateWithLifecycle()
            KavachTheme {
                CompositionLocalProvider(LocalIsOnline provides online, LocalReduceMotion provides reduceMotion) {
                    KavachRoot(root)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = DeepLinks.parse(intent)
        showOverLockScreen(link)
        root.onDeepLink(link)
    }

    override fun onResume() {
        super.onResume()
        reduceMotion = reduceMotionSetting()
    }

    override fun onStop() {
        super.onStop()
        // Showing over the lock screen is only for the emergency alert that opened the app. Once
        // the user leaves (or locks the phone) the app needs an unlock again.
        showOverLockScreen(null)
    }

    /**
     * Only an emergency alert (the full-screen notification for an incident or a medical alert)
     * may open over the lock screen and turn the screen on. Never set in the manifest: there it
     * would put the whole app (residents' flats and phone numbers) over the lock screen whenever
     * it was open when the phone locked.
     */
    private fun showOverLockScreen(link: DeepLink?) {
        val on = link?.target == LinkTarget.Incident || link?.target == LinkTarget.MedicalAlert
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
}
