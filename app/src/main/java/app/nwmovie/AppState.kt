package app.nwmovie

import kotlinx.coroutines.flow.MutableStateFlow

/** Small shared UI state that the Activity and screens both need. */
object AppState {
    val inPip = MutableStateFlow(false)
    val locked = MutableStateFlow(false)
    /** True while the player screen is open (used to auto-enter Picture-in-Picture). */
    @Volatile var playerOpen = false
    @Volatile var playerPlaying = false
    /** "Switch version": the player that opens next continues at this position. */
    @Volatile var switchTo: String? = null
    @Volatile var switchMs: Long = 0L
}
