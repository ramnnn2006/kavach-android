package app.kavach.android.core.data.net

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The app came to the foreground (the website's 'visibilitychange' → visible). */
@Singleton
class AppForeground @Inject constructor() {
    private val _resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits each time the app comes to the foreground. */
    val resumed: SharedFlow<Unit> = _resumed.asSharedFlow()
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Called once from KavachApp.onCreate (main thread). */
    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isForeground.value = true
                _resumed.tryEmit(Unit)
            }
            override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
        })
    }
}
