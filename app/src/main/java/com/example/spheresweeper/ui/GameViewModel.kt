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
import com.example.spheresweeper.logic.BoardFeature
import com.example.spheresweeper.logic.BoardFeatures
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
import kotlinx.coroutines.CancellationException
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

enum class Screen { MENU, CAMPAIGN, GAME, HOW_TO }

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
 * Feature cards shown over the board, one at a time: [features] in order, [index] the one on screen.
 * [intro] cards introduce features the player has not seen yet, and closing one marks its feature as
 * seen. Cards opened from the HUD ([intro] false) leave the seen features as they are.
 */
data class FeatureCards(val features: List<BoardFeature>, val index: Int, val intro: Boolean) {
    val current: BoardFeature get() = features[index]
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
     * False until the player's first move in this game (reveal, flag, auto flag, chord or hint). The game opens
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

    /**
     * Revealed far cell whose scope the ball highlights, -1 for none. A tap on it sets it (see
     * [singleTap] and [tapImmediate]). The next move, a tap on another cell, a new board and the end
     * of the game clear it; turning and zooming the ball keep it.
     */
    var highlightCell by mutableIntStateOf(-1)
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

    /** Features of the current board, in [BoardFeature] order; empty while no board exists. */
    var boardFeatures by mutableStateOf<List<BoardFeature>>(emptyList())
        private set

    /** Feature cards on screen, or null. While they are open the board takes no moves and the timer stands. */
    var featureCards by mutableStateOf<FeatureCards?>(null)
        private set

    /** Start cell of the current board, pulsed once the board is in view; -1 while no board exists. */
    private var startCell = -1

    /** Features whose introduction card the player has closed. */
    private var seenFeatures: Set<BoardFeature> = prefs.seenFeatures

    private var timerStartRealtime = 0L

    /** elapsedRealtime when feature cards paused the running timer; 0 while it is not paused. */
    private var pausedAtRealtime = 0L

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

    /**
     * Starts the level after the current one, if it is open. The finished board stays in view until
     * the next one is ready; taps while it loads are ignored, so a double tap never skips a level.
     */
    fun startNextLevel() {
        if (loading || screen != Screen.GAME) return
        val next = nextLevel ?: return
        start(GameConfig.Level(next), keepBoard = board != null)
    }

    /**
     * Shows the game screen and loads a game for [newConfig]. With [keepBoard] the board on screen
     * stays in view until the new one is ready (see [loadGame]); a game started from the menu or the
     * campaign map clears it, since it belongs to a game the player has left.
     */
    private fun start(newConfig: GameConfig, keepBoard: Boolean = false) {
        config = newConfig
        screen = Screen.GAME
        zoom = 1f
        lastRecord = null
        loadGame(keepBoard)
    }

    /**
     * New board for the same config; a campaign level comes back exactly as it was. The current board
     * stays in view until the new one is ready.
     */
    fun restart() {
        if (geometry != null) loadGame(keepBoard = board != null)
    }

    /** True while [loadGame] builds the next game; a board still in view then takes no moves. */
    private val loading: Boolean get() = loadJob?.isActive == true

    /**
     * Replaces the current game with a new one for [config], generated off the main thread. While it
     * loads, the timer stands and no move, hint or card applies.
     *
     * With [keepBoard] the board, its layout and geometry stay in view, frozen, so a quick load swaps
     * the boards without a loading text in between. Only when the load takes longer than
     * [LOADING_TEXT_DELAY_MILLIS] is the board cleared, and the game screen shows its loading text.
     * Without [keepBoard] the board is cleared at once.
     *
     * The geometry comes from [geometryCache] or is built together with the game. A newer call, or
     * leaving the game, cancels a load still running, and its result is dropped; a result for a
     * config that is no longer current is dropped too.
     */
    private fun loadGame(keepBoard: Boolean) {
        val forConfig = config
        val cached = geometryCache[forConfig.frequency]
        loadJob?.cancel()
        stopTimer()
        featureCards = null
        highlightCell = -1
        startCell = -1
        pausedAtRealtime = 0L
        if (!keepBoard) clearBoard(cached)
        loadJob = scope.launch {
            // A child of the load: cancelled with it, or once the new game is ready.
            val slow = if (keepBoard) {
                launch {
                    delay(LOADING_TEXT_DELAY_MILLIS)
                    clearBoard(cached)
                }
            } else {
                null
            }
            // withContext drops the result once this job is cancelled, so a newer load always wins.
            val (built, game) = withContext(Dispatchers.Default) {
                val g = cached ?: SphereGeometry.build(forConfig.frequency)
                g to prepareGame(g, forConfig)
            }
            slow?.cancel()
            geometryCache[forConfig.frequency] = built
            if (config == forConfig) {
                geometry = built
                newBoard(game)
            }
        }
    }

    /** Removes the board from view, so the game screen shows its loading text; [g] may be null. */
    private fun clearBoard(g: SphereGeometry?) {
        geometry = g
        board = null
        layout = null
        boardFeatures = emptyList()
        highlightCell = -1
    }

    fun openCampaign() {
        screen = Screen.CAMPAIGN
    }

    /** Opens the legend of cell marks and rules; back returns to the menu through [backToMenu]. */
    fun openHowTo() {
        screen = Screen.HOW_TO
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

    // ---------------------------------------------------------------- feature cards

    /**
     * Opens the cards of every feature of the current board, seen before or not, and pauses a running
     * timer. Does nothing when the board has no features or cards are already open.
     */
    fun openFeatureHelp() {
        if (screen != Screen.GAME || loading || featureCards != null || boardFeatures.isEmpty()) return
        featureCards = FeatureCards(boardFeatures, 0, intro = false)
        pauseTimer()
    }

    /**
     * Closes the card on screen and shows the next one. Closing an intro card stores its feature as
     * seen. After the last card the board takes moves again and a paused timer goes on. Closing the
     * last intro card pulses the start cell, which the cards covered when the board appeared.
     */
    fun closeFeatureCard() {
        val cards = featureCards ?: return
        if (cards.intro && cards.current !in seenFeatures) {
            seenFeatures = seenFeatures + cards.current
            prefs.seenFeatures = seenFeatures
        }
        if (cards.index + 1 < cards.features.size) {
            featureCards = cards.copy(index = cards.index + 1)
        } else {
            featureCards = null
            resumeTimer()
            if (cards.intro && startCell >= 0) startEffect(SphereEffect.HINT, startCell)
        }
    }

    // ---------------------------------------------------------------- view

    fun notifyOrientationChanged() {
        viewVersion++
    }

    fun updateZoom(value: Float) {
        zoom = value.coerceIn(SphereCamera.MIN_ZOOM, SphereCamera.MAX_ZOOM)
    }

    // ---------------------------------------------------------------- moves

    /**
     * Single tap in double-tap mode (delayed, or the first tap of a broken double tap). On a revealed
     * far cell it only switches the highlight of its scope on or off (see [highlightCell]). On any
     * other cell it clears the highlight and reveals the cell if it is covered.
     */
    fun singleTap(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        if (b.isRevealed(cell) && isFarCell(cell)) {
            toggleHighlight(cell)
            return Feedback.NONE
        }
        highlightCell = -1
        return afterMove(b.reveal(cell), Feedback.NONE)
    }

    /**
     * Double tap: flag a covered cell, chord a revealed number. A double tap on another cell than the
     * highlighted one clears the highlight; on the highlighted cell only a chord that acts clears it.
     */
    fun doubleTap(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        if (cell != highlightCell) highlightCell = -1
        return if (b.isRevealed(cell)) chordOn(b, cell) else toggleFlagOn(b, cell)
    }

    /**
     * Long press, in both modes. On a revealed number it flags every covered cell of its scope when
     * they must all be mines ([MinesweeperBoard.autoFlagCells]); otherwise nothing changes and the
     * result is [Feedback.REJECT], also for a closed lock. A revealed 0 is ignored without feedback.
     * On a covered cell it toggles the flag in long-press mode and does nothing in double-tap mode.
     */
    fun longPress(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        if (b.isRevealed(cell)) return autoFlagOn(b, cell)
        return if (flagByLongPress) toggleFlagOn(b, cell) else Feedback.NONE
    }

    /**
     * Immediate tap in long-press mode: reveal a covered cell, chord a revealed number.
     *
     * A revealed far cell chords only when the chord would reveal something ([MinesweeperBoard.canChord]:
     * as many known mines in its scope as its number and at least one covered cell without a flag).
     * Otherwise the tap switches the highlight of its scope on or off, like [singleTap] does. A tap on
     * any other cell clears the highlight.
     */
    fun tapImmediate(cell: Int, forGame: Int): Feedback {
        val b = activeBoard(cell, forGame) ?: return Feedback.NONE
        if (b.isRevealed(cell) && isFarCell(cell) && !b.canChord(cell)) {
            toggleHighlight(cell)
            return Feedback.NONE
        }
        if (cell != highlightCell) highlightCell = -1
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
        if (loading || featureCards != null) return Feedback.NONE
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
     * game, a board kept in view while the next one loads, a finished game, open feature cards, or a
     * cell that is not in play (out of range or ocean).
     */
    private fun activeBoard(cell: Int, forGame: Int): MinesweeperBoard? {
        val b = board ?: return null
        if (screen != Screen.GAME || forGame != gameId || loading || featureCards != null) return null
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

    /** Flags the cells [MinesweeperBoard.autoFlagCells] finds around the revealed [cell]; see [longPress]. */
    private fun autoFlagOn(b: MinesweeperBoard, cell: Int): Feedback {
        if (b.isLockOpen(cell) && b.adjacentMines(cell) == 0) return Feedback.NONE
        val cells = b.autoFlagCells(cell)
        if (cells == null || cells.isEmpty()) return Feedback.REJECT
        for (target in cells) b.toggleFlag(target)
        onPlayerMove()
        boardVersion++
        return Feedback.FLAG
    }

    private fun isFarCell(cell: Int): Boolean = layout?.isFar(cell) == true

    /** Highlights the scope of the far [cell], or clears the highlight when it is already on [cell]. */
    private fun toggleHighlight(cell: Int) {
        highlightCell = if (highlightCell == cell) -1 else cell
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

    /** Marks that the player has acted in this game; the first move starts the timer and clears the highlight. */
    private fun onPlayerMove() {
        highlightCell = -1
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
     * revealed right away. A free game draws a new board every time, with the ocean, rooms, portals and
     * locks of its [BoardSize] and the size's mine density on the drawn land ([BoardSize.minesFor]); a
     * level is built from its seed.
     */
    private fun prepareGame(g: SphereGeometry, forConfig: GameConfig): PreparedGame = when (forConfig) {
        is GameConfig.Free -> prepareRandomGame(g, forConfig.size)
        is GameConfig.Level -> {
            val level = Campaign.buildLevel(forConfig.spec, g)
            PreparedGame(level.layout, level.board, level.start)
        }
    }

    /**
     * A free game of [size] on [g], drawn with fresh randomness on every attempt. Both generators can
     * fail on a rare draw: [BoardLayout.generate] when an island cannot be split into the asked rooms or
     * no portal fits, [NoGuessGenerator.generate] when it runs out of layouts. A new draw almost always
     * succeeds, so a failure is retried up to [RANDOM_GAME_ATTEMPTS] times. Rooms cause most of these
     * failures, so after that the game is drawn without rooms (the size's walls, ocean, portals and
     * locks stay; the locks then count the whole board), again with up to [RANDOM_GAME_ATTEMPTS] draws.
     * Only a size that fails every draw, which points to a broken [BoardSize], passes the last error
     * on. Campaign levels never come here: they are built from fixed seeds that the tests cover.
     */
    private fun prepareRandomGame(g: SphereGeometry, size: BoardSize): PreparedGame {
        var failure: RuntimeException? = null
        for (attempt in 0 until 2 * RANDOM_GAME_ATTEMPTS) {
            val roomCount = if (attempt < RANDOM_GAME_ATTEMPTS) size.rooms else 0
            try {
                return drawRandomGame(g, size, roomCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                failure = e
            } catch (e: IllegalArgumentException) {
                failure = e
            }
        }
        throw checkNotNull(failure)
    }

    /** One draw of a free game of [size] on [g] with [roomCount] rooms; throws when a generator fails. */
    private fun drawRandomGame(g: SphereGeometry, size: BoardSize, roomCount: Int): PreparedGame {
        val newLayout = BoardLayout.generate(
            g, size.walls, size.wallMinLength, size.wallMaxLength, size.farCells,
            lineCount = size.lineCells, islandCount = size.islands, portalCount = size.portals,
            roomCount = roomCount,
        )
        val mines = size.minesFor(newLayout)
        val generated = NoGuessGenerator.generate(newLayout, mines, lockCount = size.locks)
        val started = MinesweeperBoard(
            newLayout.scopes, mines, lives = size.lives,
            present = newLayout.presentMask(), lockThresholds = generated.lockThresholds,
            lockRegions = generated.lockRegions,
        )
        started.startWith(generated.mines, generated.start)
        return PreparedGame(newLayout, started, generated.start)
    }

    /**
     * Shows [game], built off the main thread by [loadGame] for the current [geometry] and [config].
     * Board and [gameId] change together, so moves meant for the old board are ignored. The ball
     * turns at once so the start cell faces the player, and a hint pulse marks it. Features of the
     * board the player has not seen yet open their intro cards, in [BoardFeature] order, before the
     * first move; the pulse then waits until the last card closes.
     */
    private fun newBoard(game: PreparedGame) {
        val g = geometry ?: return
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
        highlightCell = -1
        pausedAtRealtime = 0L
        val features = BoardFeatures.of(game.layout, game.board).toList()
        boardFeatures = features
        val pendingIntro = features.filter { it !in seenFeatures }
        featureCards = if (pendingIntro.isEmpty()) null else FeatureCards(pendingIntro, 0, intro = true)
        gameId++
        boardVersion++
        startCell = game.start
        focusOn(g, game.start, animate = false)
        if (featureCards == null) {
            startEffect(SphereEffect.HINT, game.start)
        } else {
            startEffect(SphereEffect.NONE, -1)
        }
    }

    private fun startTimer() {
        timerStartRealtime = SystemClock.elapsedRealtime()
        elapsedSeconds = 0L
        launchTicker()
    }

    private fun launchTicker() {
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

    /** Stops a running timer so that the time until [resumeTimer] does not count. */
    private fun pauseTimer() {
        if (tickerJob == null) return
        stopTimer()
        pausedAtRealtime = SystemClock.elapsedRealtime()
    }

    /** Goes on with a timer stopped by [pauseTimer], leaving the paused time out. */
    private fun resumeTimer() {
        if (pausedAtRealtime == 0L) return
        timerStartRealtime += SystemClock.elapsedRealtime() - pausedAtRealtime
        pausedAtRealtime = 0L
        launchTicker()
    }

    private fun finishGame(won: Boolean) {
        stopTimer()
        highlightCell = -1
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

        /** How long a board kept in view may wait for the next one before the loading text replaces it. */
        const val LOADING_TEXT_DELAY_MILLIS = 300L

        /** Draws of a free game with its rooms, and then again without rooms, before giving up. */
        const val RANDOM_GAME_ATTEMPTS = 5

        /** Cells closer than this to the view centre (radians) are not worth turning the ball for. */
        const val FOCUS_MIN_ANGLE = 0.35f
    }
}
