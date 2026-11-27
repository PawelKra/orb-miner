package com.example.spheresweeper.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.AndroidViewModel
import com.example.spheresweeper.data.GamePreferences
import com.example.spheresweeper.logic.BoardSize
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.MoveResult
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { MENU, GAME }

/**
 * Holds the whole game state so it survives configuration changes (device rotation):
 * current screen, board, timer, settings, best times and the ball orientation and zoom.
 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = GamePreferences(application)

    // Own main-thread scope, cancelled in onCleared().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val geometryCache = HashMap<BoardSize, SphereGeometry>()
    private var loadJob: Job? = null
    private var tickerJob: Job? = null

    var screen by mutableStateOf(Screen.MENU)
        private set

    var boardSize by mutableStateOf(BoardSize.SMALL)
        private set

    var geometry by mutableStateOf<SphereGeometry?>(null)
        private set

    var board by mutableStateOf<MinesweeperBoard?>(null)
        private set

    /** Bumped after every change of the (mutable) board so Compose redraws. */
    var boardVersion by mutableIntStateOf(0)
        private set

    /** Identifies the current game; delayed taps from an older game are ignored. */
    var gameId by mutableIntStateOf(0)
        private set

    /** Ball orientation (model to world). Mutated in place; [viewVersion] signals changes. */
    val orientation = Quaternion()

    var viewVersion by mutableIntStateOf(0)
        private set

    var zoom by mutableFloatStateOf(1f)
        private set

    /** Elapsed whole seconds shown by the HUD. */
    var elapsedSeconds by mutableLongStateOf(0L)
        private set

    /** Final time of the finished game in milliseconds. */
    var finalTimeMillis by mutableLongStateOf(0L)
        private set

    var isNewBest by mutableStateOf(false)
        private set

    var effect by mutableStateOf(SphereEffect.NONE)
        private set

    /** System.nanoTime() when [effect] started. */
    var effectStartNanos by mutableLongStateOf(0L)
        private set

    var flagByLongPress by mutableStateOf(prefs.flagByLongPress)
        private set

    var hapticsEnabled by mutableStateOf(prefs.hapticsEnabled)
        private set

    val bestTimes: SnapshotStateMap<BoardSize, Long> = mutableStateMapOf<BoardSize, Long>().also {
        it.putAll(prefs.loadBestTimes())
    }

    private var timerStartRealtime = 0L

    /** Current status; reading it subscribes the caller to board changes. */
    val status: GameStatus
        get() {
            boardVersion
            return board?.status ?: GameStatus.READY
        }

    /** Mines minus flags; reading it subscribes the caller to board changes. */
    val minesRemaining: Int
        get() {
            boardVersion
            return board?.minesRemaining ?: boardSize.mines
        }

    // ---------------------------------------------------------------- navigation

    fun startGame(size: BoardSize) {
        boardSize = size
        screen = Screen.GAME
        zoom = 1f
        val cached = geometryCache[size]
        loadJob?.cancel()
        if (cached != null) {
            geometry = cached
            newBoard()
        } else {
            geometry = null
            board = null
            stopTimer()
            loadJob = scope.launch {
                val built = withContext(Dispatchers.Default) { SphereGeometry.build(size.frequency) }
                geometryCache[size] = built
                if (boardSize == size) {
                    geometry = built
                    newBoard()
                }
            }
        }
    }

    fun restart() {
        if (geometry != null) newBoard()
    }

    fun backToMenu() {
        loadJob?.cancel()
        stopTimer()
        screen = Screen.MENU
    }

    /** Geometry for decorative use (menu orb); built synchronously, cheap for small sizes. */
    fun geometryFor(size: BoardSize): SphereGeometry =
        geometryCache.getOrPut(size) { SphereGeometry.build(size.frequency) }

    // ---------------------------------------------------------------- settings

    fun updateFlagByLongPress(enabled: Boolean) {
        flagByLongPress = enabled
        prefs.flagByLongPress = enabled
    }

    fun updateHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
        prefs.hapticsEnabled = enabled
    }

    // ---------------------------------------------------------------- view

    fun notifyOrientationChanged() {
        viewVersion++
    }

    fun updateZoom(value: Float) {
        zoom = value.coerceIn(SphereCamera.MIN_ZOOM, SphereCamera.MAX_ZOOM)
    }

    // ---------------------------------------------------------------- moves

    /** Plain reveal (delayed single tap, or the first tap of a broken double tap). */
    fun reveal(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        return afterMove(b.reveal(cell), Feedback.NONE)
    }

    /** Double tap: flag a covered cell, chord a revealed number. */
    fun doubleTap(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        return if (b.isRevealed(cell)) chordOn(b, cell) else toggleFlagOn(b, cell)
    }

    /** Long press in long-press mode: toggle the flag of a covered cell. */
    fun longPress(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        return if (b.isRevealed(cell)) Feedback.NONE else toggleFlagOn(b, cell)
    }

    /** Immediate tap in long-press mode: reveal a covered cell, chord a revealed number. */
    fun tapImmediate(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        return if (b.isRevealed(cell)) chordOn(b, cell) else afterMove(b.reveal(cell), Feedback.NONE)
    }

    private fun activeBoard(cell: Int, forGame: Int): MinesweeperBoard? {
        val b = board ?: return null
        if (screen != Screen.GAME || forGame != gameId) return null
        if (cell < 0 || cell >= b.cellCount) return null
        if (b.status == GameStatus.WON || b.status == GameStatus.LOST) return null
        return b
    }

    private fun toggleFlagOn(b: MinesweeperBoard, cell: Int): Feedback {
        if (!b.toggleFlag(cell)) return Feedback.NONE
        boardVersion++
        return Feedback.FLAG
    }

    private fun chordOn(b: MinesweeperBoard, cell: Int): Feedback {
        if (!b.canChord(cell)) return Feedback.NONE
        return afterMove(b.chord(cell), Feedback.CHORD)
    }

    private fun afterMove(result: MoveResult, success: Feedback): Feedback {
        if (result == MoveResult.NOTHING) return Feedback.NONE
        val b = board
        if (b != null && b.status != GameStatus.READY && timerStartRealtime == 0L) {
            startTimer()
        }
        boardVersion++
        return when (result) {
            MoveResult.NOTHING -> Feedback.NONE
            MoveResult.REVEALED -> success
            MoveResult.WON -> {
                finishGame(won = true)
                success
            }
            MoveResult.LOST -> {
                finishGame(won = false)
                Feedback.EXPLOSION
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle of a game

    private fun newBoard() {
        val g = geometry ?: return
        stopTimer()
        timerStartRealtime = 0L
        elapsedSeconds = 0L
        finalTimeMillis = 0L
        isNewBest = false
        effect = SphereEffect.NONE
        effectStartNanos = 0L
        board = MinesweeperBoard(g.neighbors, boardSize.mines)
        gameId++
        boardVersion++
    }

    private fun startTimer() {
        timerStartRealtime = SystemClock.elapsedRealtime()
        elapsedSeconds = 0L
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val seconds = (SystemClock.elapsedRealtime() - timerStartRealtime) / 1000L
                if (seconds != elapsedSeconds) elapsedSeconds = seconds
                delay(TICK_MILLIS)
            }
        }
    }

    private fun stopTimer() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun finishGame(won: Boolean) {
        stopTimer()
        // At least 1 ms so that a stored best time is always a valid positive value.
        val elapsed = if (timerStartRealtime == 0L) {
            1L
        } else {
            (SystemClock.elapsedRealtime() - timerStartRealtime).coerceAtLeast(1L)
        }
        finalTimeMillis = elapsed
        elapsedSeconds = elapsed / 1000L
        effect = if (won) SphereEffect.WIN else SphereEffect.EXPLOSION
        effectStartNanos = System.nanoTime()
        if (won) {
            val previous = bestTimes[boardSize]
            if (previous == null || elapsed < previous) {
                bestTimes[boardSize] = elapsed
                prefs.saveBestTime(boardSize, elapsed)
                isNewBest = true
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val TICK_MILLIS = 100L
    }
}
