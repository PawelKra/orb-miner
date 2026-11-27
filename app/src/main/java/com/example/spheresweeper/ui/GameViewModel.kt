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
import com.example.spheresweeper.logic.BoardLayout
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
import kotlin.math.acos
import kotlin.math.sqrt

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
    private var focusJob: Job? = null
    private val focusRotation = Quaternion()
    private val scratch = FloatArray(3)

    var screen by mutableStateOf(Screen.MENU)
        private set

    var boardSize by mutableStateOf(BoardSize.SMALL)
        private set

    var geometry by mutableStateOf<SphereGeometry?>(null)
        private set

    var board by mutableStateOf<MinesweeperBoard?>(null)
        private set

    /** Walls and far cells of the current game. */
    var layout by mutableStateOf<BoardLayout?>(null)
        private set

    /** Hints the player can still use in this game. */
    var hintsLeft by mutableIntStateOf(0)
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

    /** Cell where a [SphereEffect.MINE_HIT] goes off or a [SphereEffect.HINT] points; -1 otherwise. */
    var effectCell by mutableIntStateOf(-1)
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

    /** Lives not yet lost; reading it subscribes the caller to board changes. */
    val livesLeft: Int
        get() {
            boardVersion
            return board?.livesLeft ?: boardSize.lives
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
            layout = null
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

    /**
     * Reveals a safe covered cell for the player, preferring cells next to the revealed area and
     * close to the front of the ball, then turns the ball so the cell faces the player.
     */
    fun useHint(): Feedback {
        val b = board ?: return Feedback.NONE
        val g = geometry ?: return Feedback.NONE
        if (screen != Screen.GAME || b.status != GameStatus.PLAYING || hintsLeft <= 0) return Feedback.NONE
        val cell = pickHintCell(b, g)
        if (cell < 0) return Feedback.NONE
        hintsLeft--
        startEffect(SphereEffect.HINT, cell)
        focusOn(g, cell)
        return afterMove(b.reveal(cell), Feedback.CHORD)
    }

    private fun pickHintCell(b: MinesweeperBoard, g: SphereGeometry): Int {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (cell in 0 until b.cellCount) {
            if (b.isMine(cell) || b.isRevealed(cell) || b.isFlagged(cell)) continue
            orientation.rotate(g.centers[cell * 3], g.centers[cell * 3 + 1], g.centers[cell * 3 + 2], scratch)
            // Facing the viewer counts in [-1, 1]; touching the revealed area outweighs it.
            var score = scratch[2]
            if (g.neighbors[cell].any { b.isRevealed(it) }) score += 2f
            if (score > bestScore) {
                bestScore = score
                best = cell
            }
        }
        return best
    }

    /** Turns the ball over [FOCUS_MILLIS] so that [cell] ends up in the middle of the view. */
    private fun focusOn(g: SphereGeometry, cell: Int) {
        orientation.rotate(g.centers[cell * 3], g.centers[cell * 3 + 1], g.centers[cell * 3 + 2], scratch)
        val px = scratch[0]
        val py = scratch[1]
        val pz = scratch[2].coerceIn(-1f, 1f)
        val total = acos(pz)
        if (total < FOCUS_MIN_ANGLE) return
        // Axis p x (0, 0, 1); for a cell straight behind the ball any axis in the screen plane works.
        var ax = py
        var ay = -px
        if (sqrt(ax * ax + ay * ay) < 1e-4f) {
            ax = 1f
            ay = 0f
        }
        focusJob?.cancel()
        focusJob = scope.launch {
            val start = SystemClock.uptimeMillis()
            var done = 0f
            while (true) {
                val t = ((SystemClock.uptimeMillis() - start).toFloat() / FOCUS_MILLIS).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                val step = total * eased - done
                done += step
                focusRotation.setFromAxisAngle(ax, ay, 0f, step)
                orientation.preMultiply(focusRotation).normalize()
                notifyOrientationChanged()
                if (t >= 1f) break
                delay(FRAME_MILLIS)
            }
        }
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
            MoveResult.MINE_HIT -> {
                startEffect(SphereEffect.MINE_HIT, b?.lastDetonatedCell ?: -1)
                Feedback.EXPLOSION
            }
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
        effectCell = -1
        focusJob?.cancel()
        hintsLeft = boardSize.hints
        val newLayout = BoardLayout.generate(g, boardSize)
        layout = newLayout
        board = MinesweeperBoard(newLayout.scopes, boardSize.mines, lives = boardSize.lives)
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
        startEffect(if (won) SphereEffect.WIN else SphereEffect.EXPLOSION, -1)
        if (won) {
            val previous = bestTimes[boardSize]
            if (previous == null || elapsed < previous) {
                bestTimes[boardSize] = elapsed
                prefs.saveBestTime(boardSize, elapsed)
                isNewBest = true
            }
        }
    }

    private fun startEffect(newEffect: SphereEffect, cell: Int) {
        effect = newEffect
        effectCell = cell
        effectStartNanos = System.nanoTime()
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val TICK_MILLIS = 100L
        const val FOCUS_MILLIS = 450f
        const val FRAME_MILLIS = 16L

        /** Cells closer than this to the view centre (radians) are not worth turning the ball for. */
        const val FOCUS_MIN_ANGLE = 0.35f
    }
}
