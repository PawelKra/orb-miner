package com.example.spheresweeper.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BoardFeaturesTest {

    private val geometries = HashMap<Int, SphereGeometry>()

    private fun geometry(frequency: Int): SphereGeometry =
        geometries.getOrPut(frequency) { SphereGeometry.build(frequency) }

    private fun featuresOf(branch: Branch): Set<BoardFeature> {
        val spec = Campaign.levels(branch).first()
        val level = Campaign.buildLevel(spec, geometry(spec.frequency))
        return BoardFeatures.of(level.layout, level.board)
    }

    @Test
    fun trunkLevelHasNoFeatures() {
        assertEquals(emptySet<BoardFeature>(), featuresOf(Branch.TRUNK))
    }

    /** Branches with rooms also show the rooms and the walls between them, and nothing else. */
    @Test
    fun firstLevelOfEachBranchShowsItsOwnFeature() {
        val rooms = setOf(BoardFeature.WALLS, BoardFeature.ROOMS)
        assertEquals(setOf(BoardFeature.WALLS), featuresOf(Branch.WALLS))
        assertEquals(setOf(BoardFeature.FAR), featuresOf(Branch.FAR))
        assertEquals(rooms + BoardFeature.LINES, featuresOf(Branch.LINES))
        assertEquals(setOf(BoardFeature.PORTALS), featuresOf(Branch.PORTALS))
        assertEquals(rooms + BoardFeature.LOCKS, featuresOf(Branch.LOCKS))
    }

    @Test
    fun islandsComeWithThePortalsThatJoinThem() {
        assertEquals(listOf(BoardFeature.PORTALS, BoardFeature.ISLANDS), featuresOf(Branch.ISLANDS).toList())
    }

    @Test
    fun roomsBringTheirWalls() {
        val g = geometry(BoardSize.SMALL.frequency)
        val layout = BoardLayout.generate(g, 0, 0, 0, 0, Random(7), roomCount = 3)
        val board = MinesweeperBoard(layout.scopes, 5, present = layout.presentMask())
        assertTrue(layout.hasRooms)
        assertEquals(listOf(BoardFeature.WALLS, BoardFeature.ROOMS), BoardFeatures.of(layout, board).toList())
    }

    @Test
    fun classicLayoutHasNoFeatures() {
        val layout = BoardLayout.classic(geometry(3))
        val board = MinesweeperBoard(layout.scopes, 10)
        assertEquals(emptySet<BoardFeature>(), BoardFeatures.of(layout, board))
    }

    @Test
    fun featuresComeInDeclarationOrder() {
        val spec = Campaign.levels(Branch.MIXED).last()
        val level = Campaign.buildLevel(spec, geometry(spec.frequency))
        val features = BoardFeatures.of(level.layout, level.board).toList()
        assertEquals(listOf(BoardFeature.WALLS, BoardFeature.ROOMS, BoardFeature.FAR, BoardFeature.LINES), features)
    }

    @Test
    fun serializeWritesNamesInDeclarationOrder() {
        assertEquals("", BoardFeatures.serialize(emptySet()))
        assertEquals(
            "WALLS,LOCKS,ISLANDS",
            BoardFeatures.serialize(setOf(BoardFeature.ISLANDS, BoardFeature.WALLS, BoardFeature.LOCKS)),
        )
    }

    @Test
    fun parseReadsWhatSerializeWrites() {
        val all = BoardFeature.values().toSet()
        assertEquals(all, BoardFeatures.parse(BoardFeatures.serialize(all)))
        for (feature in BoardFeature.values()) {
            assertEquals(setOf(feature), BoardFeatures.parse(BoardFeatures.serialize(setOf(feature))))
        }
    }

    @Test
    fun parseSkipsUnknownNamesBlanksAndRepeats() {
        assertEquals(emptySet<BoardFeature>(), BoardFeatures.parse(null))
        assertEquals(emptySet<BoardFeature>(), BoardFeatures.parse(""))
        assertEquals(emptySet<BoardFeature>(), BoardFeatures.parse(" , ,"))
        assertEquals(
            listOf(BoardFeature.FAR, BoardFeature.PORTALS),
            BoardFeatures.parse("PORTALS, TELEPORTS,,far,FAR ,PORTALS").toList(),
        )
    }
}
