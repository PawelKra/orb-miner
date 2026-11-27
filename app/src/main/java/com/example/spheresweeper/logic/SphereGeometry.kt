package com.example.spheresweeper.logic

import kotlin.math.sqrt

/**
 * Cell layout of a Goldberg-style sphere: the dual of an icosahedron subdivided with a given frequency.
 *
 * Every vertex of the subdivided icosahedron becomes one cell. The polygon of a cell is formed by the
 * centroids (projected onto the unit sphere) of the mesh triangles around that vertex. The 12 original
 * icosahedron vertices produce pentagons, every other vertex produces a hexagon.
 */
class SphereGeometry private constructor(
    val frequency: Int,
    /** x,y,z of each cell center (unit vector), size 3 * cellCount. */
    val centers: FloatArray,
    /** Corners of cell i are corner indices cornerStart[i] until cornerStart[i + 1]. Size cellCount + 1. */
    val cornerStart: IntArray,
    /** x,y,z of every corner (unit vectors), size 3 * cornerStart[cellCount].
     *  Corners of each cell are ordered counter-clockwise when viewed from outside the sphere. */
    val corners: FloatArray,
    /** neighbors[i] = indices of cells adjacent to i (5 or 6 entries), sorted ascending. */
    val neighbors: Array<IntArray>,
) {
    val cellCount: Int = centers.size / 3

    fun cornerCount(cell: Int): Int = cornerStart[cell + 1] - cornerStart[cell]

    fun isPentagon(cell: Int): Boolean = neighbors[cell].size == 5

    companion object {
        private const val ICOSA_VERTEX_COUNT = 12
        private const val ICOSA_FACE_COUNT = 20
        private const val ICOSA_EDGE_COUNT = 30

        /** Maximum number of triangles around one mesh vertex (hexagon). */
        private const val MAX_VALENCE = 6

        private val PHI = (1.0 + sqrt(5.0)) / 2.0

        /** Icosahedron vertices (not normalised), x,y,z interleaved. */
        private val ICOSA_VERTICES = doubleArrayOf(
            -1.0, PHI, 0.0,
            1.0, PHI, 0.0,
            -1.0, -PHI, 0.0,
            1.0, -PHI, 0.0,
            0.0, -1.0, PHI,
            0.0, 1.0, PHI,
            0.0, -1.0, -PHI,
            0.0, 1.0, -PHI,
            PHI, 0.0, -1.0,
            PHI, 0.0, 1.0,
            -PHI, 0.0, -1.0,
            -PHI, 0.0, 1.0,
        )

        /** Icosahedron faces as vertex index triples. Orientation is normalised in [build]. */
        private val ICOSA_FACES = intArrayOf(
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
        )

        /**
         * Builds the sphere for subdivision [frequency] (n). The result has 10 * n^2 + 2 cells.
         *
         * Vertices are deduplicated exactly through integer indexing: the 12 icosahedron vertices come
         * first, then the n - 1 interior points of each of the 30 edges, then the interior lattice points
         * of each of the 20 faces. Points on a shared edge are addressed through the edge, so both faces
         * see the same vertex index.
         */
        fun build(frequency: Int): SphereGeometry {
            require(frequency >= 1) { "frequency must be >= 1, was $frequency" }
            val n = frequency
            val vertexCount = 10 * n * n + 2

            // Orient every face counter-clockwise when viewed from outside.
            val faces = ICOSA_FACES.copyOf()
            for (f in 0 until ICOSA_FACE_COUNT) {
                val a = faces[3 * f]
                val b = faces[3 * f + 1]
                val c = faces[3 * f + 2]
                if (icosaOrientation(a, b, c) < 0.0) {
                    faces[3 * f + 1] = c
                    faces[3 * f + 2] = b
                }
            }

            // Enumerate the 30 icosahedron edges. edgeOf is symmetric: edgeOf[u * 12 + v] == edgeOf[v * 12 + u].
            val edgeOf = IntArray(ICOSA_VERTEX_COUNT * ICOSA_VERTEX_COUNT) { -1 }
            val edgeLow = IntArray(ICOSA_EDGE_COUNT)
            val edgeHigh = IntArray(ICOSA_EDGE_COUNT)
            var edgeCount = 0
            for (f in 0 until ICOSA_FACE_COUNT) {
                for (e in 0 until 3) {
                    val u = faces[3 * f + e]
                    val v = faces[3 * f + (e + 1) % 3]
                    val low = minOf(u, v)
                    val high = maxOf(u, v)
                    if (edgeOf[low * ICOSA_VERTEX_COUNT + high] < 0) {
                        check(edgeCount < ICOSA_EDGE_COUNT) { "icosahedron has more than 30 edges" }
                        edgeOf[low * ICOSA_VERTEX_COUNT + high] = edgeCount
                        edgeOf[high * ICOSA_VERTEX_COUNT + low] = edgeCount
                        edgeLow[edgeCount] = low
                        edgeHigh[edgeCount] = high
                        edgeCount++
                    }
                }
            }
            check(edgeCount == ICOSA_EDGE_COUNT) { "icosahedron must have 30 edges, found $edgeCount" }

            val edgeBase = ICOSA_VERTEX_COUNT
            val faceBase = edgeBase + ICOSA_EDGE_COUNT * (n - 1)

            // Local index of interior face lattice point (i, j), i >= 1, j >= 1, i + j <= n - 1.
            val stride = n + 1
            val interiorIndex = IntArray(stride * stride) { -1 }
            var perFace = 0
            for (j in 1 until n) {
                for (i in 1 until n - j) {
                    interiorIndex[i * stride + j] = perFace
                    perFace++
                }
            }
            check(faceBase + ICOSA_FACE_COUNT * perFace == vertexCount) { "vertex count mismatch" }

            val positions = DoubleArray(vertexCount * 3)

            // Original vertices.
            for (v in 0 until ICOSA_VERTEX_COUNT) {
                positions[3 * v] = ICOSA_VERTICES[3 * v]
                positions[3 * v + 1] = ICOSA_VERTICES[3 * v + 1]
                positions[3 * v + 2] = ICOSA_VERTICES[3 * v + 2]
            }
            // Edge interior points, k steps from the lower-indexed endpoint.
            for (e in 0 until ICOSA_EDGE_COUNT) {
                val lo = edgeLow[e]
                val hi = edgeHigh[e]
                for (k in 1 until n) {
                    val t = k.toDouble() / n
                    val idx = edgeBase + e * (n - 1) + (k - 1)
                    for (d in 0 until 3) {
                        val p = ICOSA_VERTICES[3 * lo + d]
                        val q = ICOSA_VERTICES[3 * hi + d]
                        positions[3 * idx + d] = p + (q - p) * t
                    }
                }
            }
            // Face interior points from barycentric weights (n - i - j, i, j) on (A, B, C).
            for (f in 0 until ICOSA_FACE_COUNT) {
                val a = faces[3 * f]
                val b = faces[3 * f + 1]
                val c = faces[3 * f + 2]
                for (j in 1 until n) {
                    for (i in 1 until n - j) {
                        val idx = faceBase + f * perFace + interiorIndex[i * stride + j]
                        val wa = (n - i - j).toDouble()
                        val wb = i.toDouble()
                        val wc = j.toDouble()
                        for (d in 0 until 3) {
                            positions[3 * idx + d] = (ICOSA_VERTICES[3 * a + d] * wa +
                                ICOSA_VERTICES[3 * b + d] * wb +
                                ICOSA_VERTICES[3 * c + d] * wc) / n
                        }
                    }
                }
            }
            // Project onto the unit sphere.
            for (v in 0 until vertexCount) {
                normalizeInPlace(positions, 3 * v)
            }

            /** Vertex index of the point k steps from icosahedron vertex u towards v (0 <= k <= n). */
            fun edgeVertex(u: Int, v: Int, k: Int): Int {
                if (k == 0) return u
                if (k == n) return v
                val e = edgeOf[u * ICOSA_VERTEX_COUNT + v]
                val stepFromLow = if (u == edgeLow[e]) k else n - k
                return edgeBase + e * (n - 1) + (stepFromLow - 1)
            }

            /** Vertex index of lattice point (i, j) of face f, where (0,0)=A, (n,0)=B, (0,n)=C. */
            fun latticeVertex(f: Int, i: Int, j: Int): Int {
                val a = faces[3 * f]
                val b = faces[3 * f + 1]
                val c = faces[3 * f + 2]
                return when {
                    j == 0 -> edgeVertex(a, b, i)
                    i == 0 -> edgeVertex(a, c, j)
                    i + j == n -> edgeVertex(b, c, j)
                    else -> faceBase + f * perFace + interiorIndex[i * stride + j]
                }
            }

            // Triangulate every face into n^2 triangles with the face's (outward) orientation.
            val triangleCount = ICOSA_FACE_COUNT * n * n
            val triangles = IntArray(triangleCount * 3)
            var t = 0
            for (f in 0 until ICOSA_FACE_COUNT) {
                for (j in 0 until n) {
                    for (i in 0 until n - j) {
                        triangles[3 * t] = latticeVertex(f, i, j)
                        triangles[3 * t + 1] = latticeVertex(f, i + 1, j)
                        triangles[3 * t + 2] = latticeVertex(f, i, j + 1)
                        t++
                        if (i + j <= n - 2) {
                            triangles[3 * t] = latticeVertex(f, i + 1, j)
                            triangles[3 * t + 1] = latticeVertex(f, i + 1, j + 1)
                            triangles[3 * t + 2] = latticeVertex(f, i, j + 1)
                            t++
                        }
                    }
                }
            }
            check(t == triangleCount) { "triangle count mismatch" }

            // Projected centroid of every triangle: these are the cell corners.
            val centroids = DoubleArray(triangleCount * 3)
            for (tri in 0 until triangleCount) {
                val a = triangles[3 * tri]
                val b = triangles[3 * tri + 1]
                val c = triangles[3 * tri + 2]
                for (d in 0 until 3) {
                    centroids[3 * tri + d] = positions[3 * a + d] + positions[3 * b + d] + positions[3 * c + d]
                }
                normalizeInPlace(centroids, 3 * tri)
            }

            // For each vertex v, record its incident triangles as (first, second, triangle) where
            // (v, first, second) is the triangle rotated to start at v, keeping its CCW orientation.
            val incidentCount = IntArray(vertexCount)
            val incidentFirst = IntArray(vertexCount * MAX_VALENCE)
            val incidentSecond = IntArray(vertexCount * MAX_VALENCE)
            val incidentTriangle = IntArray(vertexCount * MAX_VALENCE)
            fun addIncident(v: Int, first: Int, second: Int, tri: Int) {
                val k = incidentCount[v]
                check(k < MAX_VALENCE) { "vertex $v has more than $MAX_VALENCE triangles" }
                incidentFirst[v * MAX_VALENCE + k] = first
                incidentSecond[v * MAX_VALENCE + k] = second
                incidentTriangle[v * MAX_VALENCE + k] = tri
                incidentCount[v] = k + 1
            }
            for (tri in 0 until triangleCount) {
                val a = triangles[3 * tri]
                val b = triangles[3 * tri + 1]
                val c = triangles[3 * tri + 2]
                addIncident(a, b, c, tri)
                addIncident(b, c, a, tri)
                addIncident(c, a, b, tri)
            }

            val cornerStart = IntArray(vertexCount + 1)
            for (v in 0 until vertexCount) {
                cornerStart[v + 1] = cornerStart[v] + incidentCount[v]
            }
            val corners = FloatArray(cornerStart[vertexCount] * 3)
            val neighbors = arrayOfNulls<IntArray>(vertexCount)

            // Walk the triangle fan around each vertex. In a CCW triangle (v, a, b) the next triangle
            // counter-clockwise around v shares the edge (v, b), i.e. it is the one whose first == b.
            for (v in 0 until vertexCount) {
                val count = incidentCount[v]
                val base = v * MAX_VALENCE
                val ring = IntArray(count)
                var k = 0
                var out = cornerStart[v]
                for (step in 0 until count) {
                    ring[step] = incidentFirst[base + k]
                    val tri = incidentTriangle[base + k]
                    corners[3 * out] = centroids[3 * tri].toFloat()
                    corners[3 * out + 1] = centroids[3 * tri + 1].toFloat()
                    corners[3 * out + 2] = centroids[3 * tri + 2].toFloat()
                    out++
                    val next = incidentSecond[base + k]
                    var found = -1
                    for (q in 0 until count) {
                        if (incidentFirst[base + q] == next) {
                            found = q
                            break
                        }
                    }
                    check(found >= 0) { "open triangle fan around vertex $v" }
                    k = found
                }
                check(k == 0) { "triangle fan around vertex $v does not close" }
                ring.sort()
                neighbors[v] = ring
            }

            val centers = FloatArray(vertexCount * 3)
            for (i in 0 until vertexCount * 3) {
                centers[i] = positions[i].toFloat()
            }

            return SphereGeometry(
                frequency = n,
                centers = centers,
                cornerStart = cornerStart,
                corners = corners,
                neighbors = Array(vertexCount) { requireNotNull(neighbors[it]) },
            )
        }

        /** Sign of dot(cross(B - A, C - A), A + B + C) for icosahedron vertices a, b, c. */
        private fun icosaOrientation(a: Int, b: Int, c: Int): Double {
            val v = ICOSA_VERTICES
            val abx = v[3 * b] - v[3 * a]
            val aby = v[3 * b + 1] - v[3 * a + 1]
            val abz = v[3 * b + 2] - v[3 * a + 2]
            val acx = v[3 * c] - v[3 * a]
            val acy = v[3 * c + 1] - v[3 * a + 1]
            val acz = v[3 * c + 2] - v[3 * a + 2]
            val nx = aby * acz - abz * acy
            val ny = abz * acx - abx * acz
            val nz = abx * acy - aby * acx
            val sx = v[3 * a] + v[3 * b] + v[3 * c]
            val sy = v[3 * a + 1] + v[3 * b + 1] + v[3 * c + 1]
            val sz = v[3 * a + 2] + v[3 * b + 2] + v[3 * c + 2]
            return nx * sx + ny * sy + nz * sz
        }

        private fun normalizeInPlace(array: DoubleArray, offset: Int) {
            val x = array[offset]
            val y = array[offset + 1]
            val z = array[offset + 2]
            val length = sqrt(x * x + y * y + z * z)
            array[offset] = x / length
            array[offset + 1] = y / length
            array[offset + 2] = z / length
        }
    }
}
