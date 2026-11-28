package com.example.spheresweeper.logic

import java.util.concurrent.CancellationException
import kotlin.random.Random

/**
 * Everything that fixes one free game: its [size], the [seed] of the [StableRandom] that draws the
 * layout and the mines, the room count passed to [BoardLayout.generate] ([rooms], which may be 0 after
 * failed draws with the size's rooms) and whether the board has walls ([walls]). [FreeGame.build] turns
 * the same spec into the same board. Without walls the board has no rooms either, since rooms are
 * separated by walls.
 */
data class FreeGameSpec(val size: BoardSize, val seed: Long, val rooms: Int, val walls: Boolean) {
    init {
        require(rooms >= 0) { "negative room count $rooms" }
        require(walls || rooms == 0) { "a board without walls has no rooms, got $rooms" }
    }
}

/** A free game ready to play: [board] already has its mines and [start] revealed. */
class PreparedFreeGame(
    val spec: FreeGameSpec,
    val layout: BoardLayout,
    /** Cell revealed at the start. */
    val start: Int,
    /** Mine cells, sorted ascending. */
    val mines: IntArray,
    val board: MinesweeperBoard,
)

/** Builds free games of a [BoardSize] from a seed, and draws new ones with fresh seeds. */
object FreeGame {

    /** Draws of a free game with its rooms, and then again without rooms, before [draw] gives up. */
    const val ATTEMPTS = 5

    /**
     * Builds the game of [spec] on [geometry] (which must have the size's frequency). The ocean, rooms,
     * walls, portals, far cells, line cells, locks, the mines and the start cell all come from one
     * [StableRandom] seeded with [FreeGameSpec.seed], the same way [Campaign.buildLevel] builds a level,
     * except that the ocean covers [BoardSize.OCEAN_SHARE] of the sphere.
     * [FreeGameSpec.walls] false leaves out the size's walls and its rooms; every other count of the
     * size stays, and its locks then count the whole board. The mine count is [BoardSize.minesFor] of
     * the built layout. A spec without rooms places the mines with `forbidEmptyLines` of
     * [NoGuessGenerator.generate], so every line cell counts at least one mine in its row. A spec with
     * rooms places them without it: there a line cell stands at a passage, and a row without mines tells
     * the player that the way into the next room is clear.
     *
     * The returned board can already be [GameStatus.WON] when the start reveals every safe cell; [draw]
     * never hands out such a game.
     *
     * @throws IllegalStateException or [IllegalArgumentException] when a generator fails on this seed;
     *   [draw] then tries another one.
     */
    fun build(spec: FreeGameSpec, geometry: SphereGeometry): PreparedFreeGame {
        val size = spec.size
        require(geometry.frequency == size.frequency) {
            "$size needs frequency ${size.frequency}, got ${geometry.frequency}"
        }
        val random = StableRandom(spec.seed)
        val drawn = BoardLayout.generate(
            geometry, if (spec.walls) size.walls else 0, size.wallMinLength, size.wallMaxLength, size.farCells,
            random, size.lineCells, size.islands, size.portals, spec.rooms, BoardSize.OCEAN_SHARE,
        )
        val mines = size.minesFor(drawn)
        val generated = NoGuessGenerator.generate(
            drawn, mines, random, size.locks, forbidEmptyLines = spec.rooms == 0,
        )
        val board = MinesweeperBoard(
            drawn.scopes, mines, lives = size.lives,
            present = drawn.presentMask(), lockThresholds = generated.lockThresholds,
            lockRegions = generated.lockRegions,
        )
        board.startWith(generated.mines, generated.start)
        return PreparedFreeGame(spec, drawn, generated.start, generated.mines, board)
    }

    /**
     * A new free game of [size] on [geometry], with or without [walls]. Every attempt takes a new seed
     * from [seeds] and calls [build]. Both generators can fail on a rare seed: [BoardLayout.generate]
     * when an island cannot be split into the asked rooms or no portal fits, [NoGuessGenerator.generate]
     * when it runs out of layouts. An attempt also fails when its board is not [GameStatus.PLAYING]
     * after the start: on a small board with few mines the start can reveal every safe cell, and such
     * a board is already [GameStatus.WON]. It fails too when its ocean has more than [maxOceanCells]
     * cells: [BoardLayout.generate] aims at [BoardSize.OCEAN_SHARE], but its islands can stop growing
     * before they reach the land it drew, which leaves more ocean. The check lives here, not in the
     * layout, because campaign levels are drawn by the same layout code and must not change. A new seed
     * almost always succeeds, so a failure is retried up to [ATTEMPTS] times. Rooms cause most of these
     * failures, so after that the game is drawn without rooms (the size's walls, ocean, portals and
     * locks stay), again with up to [ATTEMPTS] seeds. A game without [walls] has no rooms from the first
     * attempt. Only a size that fails every attempt, which points to a broken [BoardSize], passes the
     * last error on.
     *
     * The returned game is always [GameStatus.PLAYING] with at most [maxOceanCells] ocean cells, so it
     * can start a [MatchEngine]. Its [PreparedFreeGame.spec] holds the seed and the room count of the
     * attempt that succeeded, so [build] gives back the same game.
     *
     * @throws IllegalStateException or [IllegalArgumentException] with the error of the last attempt
     *   when every attempt fails; a board that is not in play or has too much ocean fails with
     *   [IllegalStateException].
     */
    fun draw(geometry: SphereGeometry, size: BoardSize, walls: Boolean, seeds: Random = Random.Default): PreparedFreeGame =
        draw(geometry, size, walls, seeds, ::build)

    /** [draw] with [builder] in place of [build], so tests can make attempts fail. */
    internal fun draw(
        geometry: SphereGeometry,
        size: BoardSize,
        walls: Boolean,
        seeds: Random,
        builder: (FreeGameSpec, SphereGeometry) -> PreparedFreeGame,
    ): PreparedFreeGame {
        var failure: RuntimeException? = null
        for (attempt in 0 until 2 * ATTEMPTS) {
            val rooms = if (walls && attempt < ATTEMPTS) size.rooms else 0
            val spec = FreeGameSpec(size, seeds.nextLong(), rooms, walls)
            try {
                val game = builder(spec, geometry)
                check(game.board.status == GameStatus.PLAYING) {
                    "the start of seed ${spec.seed} leaves the board ${game.board.status}"
                }
                val cells = game.layout.scopes.size
                val ocean = cells - game.layout.presentCount
                check(ocean <= maxOceanCells(cells)) {
                    "the ocean of seed ${spec.seed} covers $ocean of $cells cells, above ${BoardSize.OCEAN_SHARE.endInclusive}"
                }
                return game
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

    /**
     * Most ocean cells a drawn free game may have on a sphere of [cellCount] cells: the share
     * [BoardSize.OCEAN_SHARE] ends at, rounded down to whole cells, so the share never goes above it.
     */
    internal fun maxOceanCells(cellCount: Int): Int = (cellCount * BoardSize.OCEAN_SHARE.endInclusive).toInt()
}
