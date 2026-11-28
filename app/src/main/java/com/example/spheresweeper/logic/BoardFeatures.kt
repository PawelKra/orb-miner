package com.example.spheresweeper.logic

/**
 * Kinds of cells and board parts beyond plain numbers and flags. Each one has a card that explains it,
 * which the game screen opens on request for a board that shows it. The declaration order is the order
 * in which the cards come.
 */
enum class BoardFeature {
    /** Walls, free ones or the walls between rooms. */
    WALLS,

    /** Rooms joined by passages ([BoardLayout.hasRooms]). */
    ROOMS,

    /** Far cells. */
    FAR,

    /** Line cells. */
    LINES,

    /** Portal pairs, including those that join islands. */
    PORTALS,

    /** Padlocks. */
    LOCKS,

    /** Ocean with more than one island. */
    ISLANDS,
}

/** Which [BoardFeature]s a board has, and the text form of a set of them for storage. */
object BoardFeatures {

    /** Features of the board made of [layout] and [board], in [BoardFeature] order. */
    fun of(layout: BoardLayout, board: MinesweeperBoard): Set<BoardFeature> {
        val result = LinkedHashSet<BoardFeature>()
        for (feature in BoardFeature.values()) {
            val present = when (feature) {
                BoardFeature.WALLS -> layout.walls.isNotEmpty()
                BoardFeature.ROOMS -> layout.hasRooms
                BoardFeature.FAR -> layout.farCount > 0
                BoardFeature.LINES -> layout.lineCount > 0
                BoardFeature.PORTALS -> layout.portalPairCount > 0
                BoardFeature.LOCKS -> board.lockCount > 0
                BoardFeature.ISLANDS -> layout.islandCount > 1
            }
            if (present) result.add(feature)
        }
        return result
    }

    /** [features] as their names in [BoardFeature] order, separated by commas; empty for no features. */
    fun serialize(features: Set<BoardFeature>): String =
        BoardFeature.values().filter { it in features }.joinToString(SEPARATOR) { it.name }

    /**
     * Reads the form of [serialize]. Unknown names, blanks and repeats are skipped, so text written by
     * a newer version with more features still reads. Null reads as no features.
     */
    fun parse(text: String?): Set<BoardFeature> {
        if (text.isNullOrBlank()) return emptySet()
        val names = text.split(SEPARATOR).map { it.trim() }.toSet()
        return BoardFeature.values().filterTo(LinkedHashSet()) { it.name in names }
    }

    private const val SEPARATOR = ","
}
