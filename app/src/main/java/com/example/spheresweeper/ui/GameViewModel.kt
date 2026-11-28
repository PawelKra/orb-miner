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
import com.example.spheresweeper.logic.Campaign
import com.example.spheresweeper.logic.CampaignProgress
import com.example.spheresweeper.logic.GameStatus
import com.example.spheresweeper.logic.LevelSpec
import com.example.spheresweeper.logic.MinesweeperBoard
import com.example.spheresweeper.logic.MoveResult
import com.example.spheresweeper.logic.NoGuessGenerator
import com.example.spheresweeper.logic.RecordResult
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import com.example.spheresweeper.ui.theme.GameTheme
import com.example.spheresweeper.ui.theme.GameThemes
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

enum class Screen { MENU, CAMPAIGN, GAME }

/**
 * What the current game is played on: a free game on a [BoardSize] or a fixed campaign level.
 * The numbers the game needs before a board exists come from here.
 */
sealed class GameConfig {
    abstract val frequency: Int

    /**
     * Mines expected before the board exists. With ocean the board puts the mine density on its real
     * land, so [MinesweeperBoard.mineCount] may differ by a few; once a board exists, it counts.
     */
    abstract val mines: Int
    abstract val lives: Int
    abstract val hints: Int

    /** Free game: a new random board of [size] on every start. */
    data class Free(val size: BoardSize) : GameConfig() {
        override val frequency: Int get() = size.frequency
        override val mines: Int get() = size.expectedMines
        override val lives: Int get() = size.lives
        override val hints: Int get() = size.hints
    }

    /** Campaign level: the same board on every start, built by [Campaign.buildLevel]. */
    data class Level(val spec: LevelSpec) : GameConfig() {
        override val frequency: Int get() = spec.frequency
        override val mines: Int get() = spec.mines
        override val lives: Int get() = spec.lives
        override val hints: Int get() = spec.hints
    }
}

/**
 * Holds the whole game state so it survives configuration changes (device rotation):
 * current screen, board, timer, settings, best times, campaign progress and the ball orientation
 * and zoom.
 */
class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = GamePreferences(application)

    // Own main-thread scope, cancelled in onCleared().
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Geometries by subdivision frequency; free games and levels of the same frequency share one. */
    private val geometryCache = HashMap<Int, SphereGeometry>()
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var focusJob: Job? = null
    private val focusRotation = Quaternion()
    private val scratch = FloatArray(3)

    var screen by mutableStateOf(Screen.MENU)
        private set

    /** Board size or campaign level of the current (or last) game. */
    var config by mutableStateOf<GameConfig>(GameConfig.Free(BoardSize.SMALL))
        private set

    var geometry by mutableStateOf<SphereGeometry?>(null)
        private set

    var board by mutableStateOf<MinesweeperBoard?>(null)
        private set

    /** Ocean, walls, portals, far cells and line cells of the current game. */
    var layout by mutableStateOf<BoardLayout?>(null)
        private set

    /** Hints the player can still use in this game. */
    var hintsLeft by mutableIntStateOf(0)
        private set

    /** Bumped after every change of the (mutable) board so Compose redraws. */
    var boardVersion by mutableIntStateOf(0)
        private set

    /**
     * False until the player's first move in this game (reveal, flag, chord or hint). The game opens
     * on a start area already revealed, so the status is PLAYING before that; the help line stays
     * and the timer waits until the player acts.
     */
    var playerMoved by mutableStateOf(false)
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

    /** Look of the game; an unknown stored identifier falls back to the default theme. */
    var theme by mutableStateOf(GameThemes.byId(prefs.themeId))
        private set

    val bestTimes: SnapshotStateMap<BoardSize, Long> = mutableStateMapOf<BoardSize, Long>().also {
        it.putAll(prefs.loadBestTimes())
    }

    /** Best stars per campaign level. */
    var progress by mutableStateOf(CampaignProgress.parse(prefs.campaignProgress))
        private set

    /** Stars of the finished campaign game, for the result card; null in free games and while playing. */
    var lastRecord by mutableStateOf<RecordResult?>(null)
        private set

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
            return board?.minesRemaining ?: config.mines
        }

    /** Lives not yet lost; reading it subscribes the caller to board changes. */
    val livesLeft: Int
        get() {
            boardVersion
            return board?.livesLeft ?: config.lives
        }

    /** The level after the current one if it is open, or null (free game, last level of a branch). */
    val nextLevel: LevelSpec?
        get() {
            val level = (config as? GameConfig.Level)?.spec ?: return null
            return Campaign.next(level)?.takeIf { progress.isUnlocked(it) }
        }

    // ---------------------------------------------------------------- navigation

    /** Starts a free game on [size]. */
    fun startGame(size: BoardSize) {
        start(GameConfig.Free(size))
    }

    /** Starts campaign [level]; a locked level is ignored. */
    fun startLevel(level: LevelSpec) {
        if (!progress.isUnlocked(level)) return
        start(GameConfig.Level(level))
    }

    /** Starts the level after the current one, if it is open. */
    fun startNextLevel() {
        nextLevel?.let { startLevel(it) }
    }

    private fun start(newConfig: GameConfig) {
        config = newConfig
        screen = Screen.GAME
        zoom = 1f
        lastRecord = null
        val cached = geometryCache[newConfig.frequency]
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
                // The first game is generated together with the geometry, off the main thread.
                val (built, game) = withContext(Dispatchers.Default) {
                    val g = SphereGeometry.build(newConfig.frequency)
                    g to prepareGame(g, newConfig)
                }
                geometryCache[newConfig.frequency] = built
                if (config == newConfig) {
                    geometry = built
                    newBoard(game)
                }
            }
        }
    }

    /** New board for the same config; a campaign level comes back exactly as it was. */
    fun restart() {
        if (geometry != null) newBoard()
    }

    fun openCampaign() {
        screen = Screen.CAMPAIGN
    }

    fun backToMenu() {
        loadJob?.cancel()
        stopTimer()
        screen = Screen.MENU
    }

    /** Leaves the game screen: to the campaign map after a level, to the menu after a free game. */
    fun exitGame() {
        loadJob?.cancel()
        stopTimer()
        screen = if (config is GameConfig.Level) Screen.CAMPAIGN else Screen.MENU
    }

    /** Geometry for decorative use (menu orb); built synchronously, cheap for small sizes. */
    fun geometryFor(size: BoardSize): SphereGeometry =
        geometryCache.getOrPut(size.frequency) { SphereGeometry.build(size.frequency) }

    // ---------------------------------------------------------------- settings

    fun updateFlagByLongPress(enabled: Boolean) {
        flagByLongPress = enabled
        prefs.flagByLongPress = enabled
    }

    fun updateHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
        prefs.hapticsEnabled = enabled
    }

    fun updateTheme(value: GameTheme) {
        theme = value
        prefs.themeId = value.id
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
            if (!b.isPresent(cell) || b.isMine(cell) || b.isRevealed(cell) || b.isFlagged(cell)) continue
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

    /**
     * Turns the ball so that [cell] ends up in the middle of the view: over [FOCUS_MILLIS], or at once
     * when [animate] is false. The animated turn skips cells already near the middle.
     */
    private fun focusOn(g: SphereGeometry, cell: Int, animate: Boolean = true) {
        orientation.rotate(g.centers[cell * 3], g.centers[cell * 3 + 1], g.centers[cell * 3 + 2], scratch)
        val px = scratch[0]
        val py = scratch[1]
        val pz = scratch[2].coerceIn(-1f, 1f)
        val total = acos(pz)
        if (animate && total < FOCUS_MIN_ANGLE) return
        // Axis p x (0, 0, 1); for a cell straight behind the ball any axis in the screen plane works.
        var ax = py
        var ay = -px
        if (sqrt(ax * ax + ay * ay) < 1e-4f) {
            ax = 1f
            ay = 0f
        }
        focusJob?.cancel()
        focusJob = null
        if (!animate) {
            focusRotation.setFromAxisAngle(ax, ay, 0f, total)
            orientation.preMultiply(focusRotation).normalize()
            notifyOrientationChanged()
            return
        }
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

    /**
     * The board a move on [cell] applies to, or null when the move must be ignored: another screen or
     * game, a finished game, or a cell that is not in play (out of range or ocean).
     */
    private fun activeBoard(cell: Int, forGame: Int): MinesweeperBoard? {
        val b = board ?: return null
        if (screen != Screen.GAME || forGame != gameId) return null
        if (cell < 0 || cell >= b.cellCount || !b.isPresent(cell)) return null
        if (b.status == GameStatus.WON || b.status == GameStatus.LOST) return null
        return b
    }

    private fun toggleFlagOn(b: MinesweeperBoard, cell: Int): Feedback {
        if (!b.toggleFlag(cell)) return Feedback.NONE
        onPlayerMove()
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
        onPlayerMove()
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

    /** Marks that the player has acted in this game; the first move starts the timer. */
    private fun onPlayerMove() {
        playerMoved = true
        if (timerStartRealtime == 0L) startTimer()
    }

    // ---------------------------------------------------------------- lifecycle of a game

    /** Board features and a board already opened at [start], ready to be shown. */
    private class PreparedGame(val layout: BoardLayout, val board: MinesweeperBoard, val start: Int)

    /**
     * Builds a game for [forConfig] on [g] (which has its frequency) without touching view-model
     * state, so it can run on any thread. Mines come from [NoGuessGenerator], which guarantees that
     * the whole board can be solved from the start cell by logic alone, and the start cell is
     * revealed right away. A free game draws a new board every time, with the ocean, portals and locks
     * of its [BoardSize] and the size's mine density on the drawn land ([BoardSize.minesFor]); a level
     * is built from its seed.
     */
    private fun prepareGame(g: SphereGeometry, forConfig: GameConfig): PreparedGame = when (forConfig) {
        is GameConfig.Free -> {
            val size = forConfig.size
            val newLayout = BoardLayout.generate(
                g, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
                lineCount = size.lineCells, islandCount = size.islands, portalCount = size.portals,
            )
            val mines = size.minesFor(newLayout)
            val generated = NoGuessGenerator.generate(newLayout, mines, lockCount = size.locks)
            val started = MinesweeperBoard(
                newLayout.scopes, mines, lives = size.lives,
                present = newLayout.presentMask(), lockThresholds = generated.lockThresholds,
            )
            started.startWith(generated.mines, generated.start)
            PreparedGame(newLayout, started, generated.start)
        }
        is GameConfig.Level -> {
            val level = Campaign.buildLevel(forConfig.spec, g)
            PreparedGame(level.layout, level.board, level.start)
        }
    }

    /**
     * Shows a new game: [prepared] if given (built for the current [geometry] and [config]),
     * otherwise one generated here. Board and [gameId] change together, so moves meant for the old
     * board are ignored. The ball turns at once so the start cell faces the player, and a hint pulse
     * marks it.
     */
    private fun newBoard(prepared: PreparedGame? = null) {
        val g = geometry ?: return
        val game = prepared ?: prepareGame(g, config)
        stopTimer()
        timerStartRealtime = 0L
        elapsedSeconds = 0L
        finalTimeMillis = 0L
        isNewBest = false
        lastRecord = null
        playerMoved = false
        hintsLeft = config.hints
        layout = game.layout
        board = game.board
        gameId++
        boardVersion++
        focusOn(g, game.start, animate = false)
        startEffect(SphereEffect.HINT, game.start)
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
        when (val current = config) {
            is GameConfig.Free -> if (won) {
                val size = current.size
                val previous = bestTimes[size]
                if (previous == null || elapsed < previous) {
                    bestTimes[size] = elapsed
                    prefs.saveBestTime(size, elapsed)
                    isNewBest = true
                }
            }
            is GameConfig.Level -> recordLevel(current.spec, won)
        }
    }

    /**
     * Stores the stars of a finished level. A loss earns none and leaves the progress as it is, but
     * still yields a [RecordResult] so the result card can show the best stars so far.
     */
    private fun recordLevel(level: LevelSpec, won: Boolean) {
        val b = board ?: return
        val hintsUsed = (config.hints - hintsLeft).coerceAtLeast(0)
        val result = progress.record(level, won, livesLost = b.detonatedCount, hintsUsed = hintsUsed)
        if (result.improved) {
            progress = result.progress
            prefs.campaignProgress = result.progress.serialize()
        }
        lastRecord = result
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
