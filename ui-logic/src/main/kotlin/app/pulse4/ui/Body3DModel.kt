package app.pulse4.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM parametric 3D human anatomy model driven by Withings Body Scan segmental composition.
 *
 * Coordinate system:
 * - Y: vertical from -1.0 (soles of feet) to +1.0 (crown of head)
 * - X: anatomical lateral axis (-X = person's RIGHT side, +X = person's LEFT side; at yaw = 0 facing the viewer,
 *      person's right arm appears on viewer's left, matching standard anatomical / mirror view)
 * - Z: anterior-posterior (+Z = front / chest / face, -Z = back / spine / glutes)
 *
 * Segment IDs match Withings Body Scan position codes:
 * - 0: neutral anatomy (head, neck, hands/feet accent)
 * - 2: Right arm (Pravá ruka)
 * - 3: Left arm (Levá ruka)
 * - 12: Trunk (Trup – hrudník a břicho)
 * - 10: Right leg (Pravá noha)
 * - 11: Left leg (Levá noha)
 */
object Body3DModel {
    const val SEG_NEUTRAL = 0
    const val SEG_RIGHT_ARM = 2
    const val SEG_LEFT_ARM = 3
    const val SEG_RIGHT_LEG = 10
    const val SEG_LEFT_LEG = 11
    const val SEG_TRUNK = 12

    val MEASURED_SEGMENTS = listOf(SEG_RIGHT_ARM, SEG_LEFT_ARM, SEG_TRUNK, SEG_RIGHT_LEG, SEG_LEFT_LEG)

    data class Vec3(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
        fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
        fun cross(o: Vec3) = Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x,
        )
        fun length(): Float = sqrt(x * x + y * y + z * z)
        fun normalized(): Vec3 {
            val l = length()
            return if (l > 1e-6f) Vec3(x / l, y / l, z / l) else Vec3(0f, 1f, 0f)
        }
    }

    /** Real body composition inputs for a single measurement date. */
    data class MorphInput(
        val muscleByPos: Map<Int, Double>,
        val fatByPos: Map<Int, Double>,
        val ffmByPos: Map<Int, Double> = emptyMap(),
        val totalWeightKg: Double? = null,
        val visceralFatIdx: Double? = null,
        /** Withings convention: 0 = male, 1 = female. */
        val gender: Int = 0,
    )

    /**
     * Synthesizes a physiologically distributed [MorphInput] from whole-body scale measurements
     * (standard Withings Body+/Body Smart/Body Comp, Garmin Index S2, Health Connect, or manual weight)
     * when 5-segment Body Scan data is not available.
     *
     * Regional muscle & fat splits follow sex-specific adult body composition norms
     * (android trunk-predominant fat for males, gynoid hip/thigh-predominant fat for females).
     */
    fun fromWholeBody(
        weightKg: Double,
        bodyFatPct: Double? = null,
        muscleMassKg: Double? = null,
        visceralFatIdx: Double? = null,
        gender: Int = 0,
    ): MorphInput {
        val isFemale = gender == 1
        val safeW = weightKg.takeIf { it.isFinite() && it in 25.0..260.0 } ?: if (isFemale) 64.0 else 82.0
        val refW = if (isFemale) 64.0 else 82.0

        // Estimate plausible body fat % from weight relative to sex baseline if scale only provides weight
        val estFatPct = bodyFatPct?.takeIf { it.isFinite() && it in 4.0..65.0 }
            ?: run {
                val basePct = if (isFemale) 26.5 else 18.0
                (basePct + ((safeW - refW) / refW) * 28.0).coerceIn(if (isFemale) 14.0 else 8.0, 52.0)
            }
        val totalFatKg = (safeW * estFatPct / 100.0).coerceIn(2.0, safeW * 0.65)
        val totalFfmKg = (safeW - totalFatKg).coerceAtLeast(safeW * 0.35)
        val totalMuscleKg = muscleMassKg?.takeIf { it.isFinite() && it in 12.0..130.0 }
            ?: (totalFfmKg * (if (isFemale) 0.91 else 0.93))

        // Regional muscle split (arms / trunk / legs)
        val armMShare = if (isFemale) 0.0475 else 0.058
        val trunkMShare = if (isFemale) 0.475 else 0.510
        val legMShare = if (isFemale) 0.215 else 0.187

        // Sex-specific fat distribution (male: android trunk-heavy; female: gynoid leg/hip-heavy)
        val armFShare = if (isFemale) 0.055 else 0.050
        val trunkFShare = if (isFemale) 0.440 else 0.560
        val legFShare = if (isFemale) 0.225 else 0.170

        val muscleMap = mapOf(
            SEG_RIGHT_ARM to totalMuscleKg * armMShare,
            SEG_LEFT_ARM to totalMuscleKg * armMShare,
            SEG_TRUNK to totalMuscleKg * trunkMShare,
            SEG_RIGHT_LEG to totalMuscleKg * legMShare,
            SEG_LEFT_LEG to totalMuscleKg * legMShare,
        )
        val fatMap = mapOf(
            SEG_RIGHT_ARM to totalFatKg * armFShare,
            SEG_LEFT_ARM to totalFatKg * armFShare,
            SEG_TRUNK to totalFatKg * trunkFShare,
            SEG_RIGHT_LEG to totalFatKg * legFShare,
            SEG_LEFT_LEG to totalFatKg * legFShare,
        )
        val ffmMap = mapOf(
            SEG_RIGHT_ARM to totalFfmKg * armMShare,
            SEG_LEFT_ARM to totalFfmKg * armMShare,
            SEG_TRUNK to totalFfmKg * trunkMShare,
            SEG_RIGHT_LEG to totalFfmKg * legMShare,
            SEG_LEFT_LEG to totalFfmKg * legMShare,
        )
        return MorphInput(
            muscleByPos = muscleMap,
            fatByPos = fatMap,
            ffmByPos = ffmMap,
            totalWeightKg = safeW,
            visceralFatIdx = visceralFatIdx,
            gender = gender,
        )
    }

    /** Reusable scratch buffers for zero-churn 60/120fps projection during drag/orbit. */
    class ProjectionScratch {
        internal var rotVx = FloatArray(0)
        internal var rotVy = FloatArray(0)
        internal var rotVz = FloatArray(0)
        internal var rotNx = FloatArray(0)
        internal var rotNy = FloatArray(0)
        internal var rotNz = FloatArray(0)
        internal var sx = FloatArray(0)
        internal var sy = FloatArray(0)

        internal fun ensureCapacity(nVerts: Int) {
            if (rotVx.size < nVerts) {
                rotVx = FloatArray(nVerts)
                rotVy = FloatArray(nVerts)
                rotVz = FloatArray(nVerts)
                rotNx = FloatArray(nVerts)
                rotNy = FloatArray(nVerts)
                rotNz = FloatArray(nVerts)
                sx = FloatArray(nVerts)
                sy = FloatArray(nVerts)
            }
        }
    }

    /** Computed composition summary for one Withings body segment. */
    data class SegmentMetrics(
        val pos: Int,
        val muscleKg: Double,
        val fatKg: Double,
        val ffmKg: Double,
        val musclePct: Double,
        val fatPct: Double,
        val shareOfTotalMusclePct: Double,
        val shareOfTotalFatPct: Double,
    )

    /** Anatomically clamped morphing multipliers derived from [MorphInput]. */
    data class MorphFactors(
        val isFemale: Boolean,
        val overallScale: Float,
        val shoulderScale: Float,
        val chestDepthScale: Float,
        val waistWidthScale: Float,
        val bellyDepthScale: Float,
        val hipWidthScale: Float,
        val rightArmMuscle: Float,
        val rightArmFat: Float,
        val leftArmMuscle: Float,
        val leftArmFat: Float,
        val rightLegMuscle: Float,
        val rightLegFat: Float,
        val leftLegMuscle: Float,
        val leftLegFat: Float,
        val trunkFatScale: Float,
        val visceralNorm: Float,
        val visceralCoreRadius: Float,
        val segments: Map<Int, SegmentMetrics>,
    )

    data class Triangle(
        val i0: Int,
        val i1: Int,
        val i2: Int,
        val segmentId: Int,
        /** Optional topological contour ring index for subtle holographic scan lines. */
        val contourEdge: Boolean = false,
    )

    data class Mesh(
        val vertices: List<Vec3>,
        val vertexNormals: List<Vec3>,
        val triangles: List<Triangle>,
        /** 3D callout anchor point on the surface of each measured segment. */
        val anchors: Map<Int, Vec3>,
        /** 3D center of the abdomen for the visceral fat core visualization. */
        val visceralCenter: Vec3,
        val factors: MorphFactors,
    )

    data class ProjectedTri(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val depth: Float,
        val segmentId: Int,
        /** Key directional diffuse intensity [0..1]. */
        val diffuse: Float,
        /** Cool secondary fill light [0..1]. */
        val fill: Float,
        /** Grazing-angle Fresnel rim factor [0..1]. */
        val fresnel: Float,
        /** Subtle specular highlight [0..1]. */
        val specular: Float,
        /** True when edge (v0->v1) lies along a horizontal anatomical contour ring. */
        val contourEdge: Boolean,
    )

    data class ProjectedAnchor(
        val segmentId: Int,
        val x: Float,
        val y: Float,
        val depth: Float,
        val frontFacing: Boolean,
    )

    data class ProjectedScene(
        val triangles: List<ProjectedTri>,
        val anchors: Map<Int, ProjectedAnchor>,
        val visceralCenterX: Float,
        val visceralCenterY: Float,
        val visceralDepth: Float,
        val visceralScreenRadius: Float,
    )

    private fun clamp(v: Float, lo: Float, hi: Float): Float = v.coerceIn(lo, hi)

    private fun safeRatio(value: Double?, ref: Double, lo: Float, hi: Float, power: Float = 0.52f): Float {
        if (value == null || !value.isFinite() || value <= 0.05 || ref <= 0.05) return 1.0f
        val r = (value / ref).toFloat().coerceAtLeast(0.15f)
        return clamp(r.pow(power), lo, hi)
    }

    /** Derives bounded, anatomically coherent morph factors from real Withings segmental data. */
    fun computeMorphFactors(input: MorphInput): MorphFactors {
        val isFemale = input.gender == 1
        // Reference baselines (typical adult Withings Body Scan averages)
        val refArmM = if (isFemale) 2.35 else 4.10
        val refArmF = if (isFemale) 1.10 else 0.95
        val refTrunkM = if (isFemale) 23.0 else 33.5
        val refTrunkF = if (isFemale) 10.5 else 10.5
        val refLegM = if (isFemale) 8.2 else 11.8
        val refLegF = if (isFemale) 3.8 else 2.8
        val refWeight = if (isFemale) 64.0 else 82.0

        val totalMuscle = MEASURED_SEGMENTS.sumOf { input.muscleByPos[it] ?: 0.0 }.coerceAtLeast(0.01)
        val totalFat = MEASURED_SEGMENTS.sumOf { input.fatByPos[it] ?: 0.0 }.coerceAtLeast(0.01)

        val segMetrics = LinkedHashMap<Int, SegmentMetrics>()
        for (pos in MEASURED_SEGMENTS) {
            val m = (input.muscleByPos[pos] ?: 0.0).coerceAtLeast(0.0)
            val f = (input.fatByPos[pos] ?: 0.0).coerceAtLeast(0.0)
            val ffm = (input.ffmByPos[pos] ?: m).coerceAtLeast(m)
            val segTot = (ffm + f).coerceAtLeast(0.01)
            segMetrics[pos] = SegmentMetrics(
                pos = pos,
                muscleKg = m,
                fatKg = f,
                ffmKg = ffm,
                musclePct = (m / segTot) * 100.0,
                fatPct = (f / segTot) * 100.0,
                shareOfTotalMusclePct = (m / totalMuscle) * 100.0,
                shareOfTotalFatPct = (f / totalFat) * 100.0,
            )
        }

        val trunkM = safeRatio(input.muscleByPos[SEG_TRUNK], refTrunkM, 0.80f, 1.32f, 0.48f)
        val trunkF = safeRatio(input.fatByPos[SEG_TRUNK], refTrunkF, 0.72f, 1.52f, 0.48f)

        val rArmM = safeRatio(input.muscleByPos[SEG_RIGHT_ARM], refArmM, 0.76f, 1.38f, 0.55f)
        val rArmF = safeRatio(input.fatByPos[SEG_RIGHT_ARM], refArmF, 0.78f, 1.46f, 0.42f)
        val lArmM = safeRatio(input.muscleByPos[SEG_LEFT_ARM], refArmM, 0.76f, 1.38f, 0.55f)
        val lArmF = safeRatio(input.fatByPos[SEG_LEFT_ARM], refArmF, 0.78f, 1.46f, 0.42f)

        val rLegM = safeRatio(input.muscleByPos[SEG_RIGHT_LEG], refLegM, 0.78f, 1.36f, 0.52f)
        val rLegF = safeRatio(input.fatByPos[SEG_RIGHT_LEG], refLegF, 0.76f, 1.48f, 0.42f)
        val lLegM = safeRatio(input.muscleByPos[SEG_LEFT_LEG], refLegM, 0.78f, 1.36f, 0.52f)
        val lLegF = safeRatio(input.fatByPos[SEG_LEFT_LEG], refLegF, 0.76f, 1.48f, 0.42f)

        // Visceral fat on Withings Body Scan is 0..20 (optimal <3.0, moderate 3..4.4, high >=4.5)
        val vfNorm = input.visceralFatIdx?.takeIf { it.isFinite() && it >= 0.0 }?.let { vf ->
            clamp(((vf - 2.0) / 8.0).toFloat(), -0.18f, 1.0f)
        } ?: ((trunkF - 1.0f) * 0.75f).coerceIn(-0.18f, 0.85f)

        val shoulderScale = clamp(0.92f * trunkM + 0.08f * ((rArmM + lArmM) * 0.5f), 0.82f, 1.32f)
        val chestDepthScale = clamp(0.75f * trunkM + 0.25f * trunkF, 0.80f, 1.38f)
        // Android (male) fat goes strongly to waist & central belly; gynoid (female) fat spares upper waist more and goes to hips/thighs/lower belly
        val waistWidthScale = if (isFemale) {
            clamp(0.38f * trunkM + 0.52f * trunkF + 0.10f * ((rLegF + lLegF) * 0.5f) + vfNorm * 0.14f, 0.78f, 1.44f)
        } else {
            clamp(0.26f * trunkM + 0.74f * trunkF + vfNorm * 0.22f, 0.78f, 1.56f)
        }
        val bellyDepthScale = if (isFemale) {
            clamp(0.26f * trunkM + 0.74f * trunkF + vfNorm * 0.26f, 0.76f, 1.54f)
        } else {
            clamp(0.16f * trunkM + 0.84f * trunkF + vfNorm * 0.34f, 0.76f, 1.72f)
        }
        val hipWidthScale = if (isFemale) {
            clamp(0.30f * ((rLegM + lLegM) * 0.5f) + 0.52f * ((rLegF + lLegF) * 0.5f) + 0.18f * trunkF, 0.84f, 1.52f)
        } else {
            clamp(0.55f * ((rLegM + lLegM) * 0.5f) + 0.45f * ((rLegF + lLegF) * 0.5f), 0.82f, 1.34f)
        }

        val overall = safeRatio(input.totalWeightKg, refWeight, 0.90f, 1.12f, 0.22f)
        val visceralRadius = clamp(0.055f + max(0f, vfNorm + 0.15f) * 0.062f, 0.045f, 0.125f)

        return MorphFactors(
            isFemale = isFemale,
            overallScale = overall,
            shoulderScale = shoulderScale,
            chestDepthScale = chestDepthScale,
            waistWidthScale = waistWidthScale,
            bellyDepthScale = bellyDepthScale,
            hipWidthScale = hipWidthScale,
            rightArmMuscle = rArmM,
            rightArmFat = rArmF,
            leftArmMuscle = lArmM,
            leftArmFat = lArmF,
            rightLegMuscle = rLegM,
            rightLegFat = rLegF,
            leftLegMuscle = lLegM,
            leftLegFat = lLegF,
            trunkFatScale = trunkF,
            visceralNorm = vfNorm,
            visceralCoreRadius = visceralRadius,
            segments = segMetrics,
        )
    }

    private data class RingSpec(
        val cx: Float,
        val cy: Float,
        val cz: Float,
        val rx: Float,
        val rz: Float,
        /** Extra forward bulge at front center (belly/quads/chest). */
        val frontBulge: Float = 0f,
        /** Bilateral anterior chest contour (peaking at ±32° front-left/front-right with gentle sternum valley). */
        val bustBulge: Float = 0f,
        /** Extra posterior bulge at back center (glutes/calves/shoulder blades). */
        val backBulge: Float = 0f,
        /** Subtle sternum / spine median indentation for muscular definition. */
        val medianGroove: Float = 0f,
        /** Slight Z-rotation tilt of the cross-section plane (radians, for angled arms). */
        val tiltZ: Float = 0f,
        /** Mark this ring as a subtle holographic contour line. */
        val contour: Boolean = false,
    )

    private class MeshBuilder {
        val verts = ArrayList<Vec3>(1200)
        val tris = ArrayList<Triangle>(2200)

        fun addLoft(
            rings: List<RingSpec>,
            radialSteps: Int,
            segmentId: Int,
            capStart: Boolean = true,
            capEnd: Boolean = true,
        ) {
            if (rings.size < 2) return
            val ringStartIndices = IntArray(rings.size)
            for (rIdx in rings.indices) {
                val r = rings[rIdx]
                ringStartIndices[rIdx] = verts.size
                val cosT = cos(r.tiltZ)
                val sinT = sin(r.tiltZ)
                for (s in 0 until radialSteps) {
                    // theta = 0 -> +X, pi/2 -> +Z (front), pi -> -X, 3pi/2 -> -Z (back)
                    val theta = (2.0 * PI * s / radialSteps).toFloat()
                    val c = cos(theta)
                    val sn = sin(theta)
                    var lx = r.rx * c
                    var lz = r.rz * sn

                    // Anatomical shaping: front bulge (sn > 0), bilateral bust contour, back bulge (sn < 0), median groove
                    if (sn > 0f && r.frontBulge != 0f) {
                        val frontWeight = sn * sn
                        lz += r.frontBulge * frontWeight
                    }
                    if (sn > 0f && r.bustBulge != 0f) {
                        // Bilateral lobes peaking around ±32° off front center (cos ≈ ±0.53, sin ≈ 0.85) with sternum valley
                        val lobe = (c * sn).let { it * it } * 4.0f
                        val frontMask = sn * sn
                        lz += r.bustBulge * frontMask * (0.22f + 0.95f * lobe)
                    }
                    if (sn < 0f && r.backBulge != 0f) {
                        val backWeight = sn * sn
                        lz -= r.backBulge * backWeight
                    }
                    if (r.medianGroove != 0f) {
                        // Gentle dip near center front (sternum) and center back (spine)
                        val centerFactor = (sn * sn).pow(3f)
                        lz *= (1f - r.medianGroove * centerFactor)
                        // Subtle pectoral / lat swell at ±45°
                        val pecFactor = (c * sn) * (c * sn)
                        lz *= (1f + r.medianGroove * 0.45f * pecFactor)
                    }

                    // Rotate (lx, 0) in XY plane by tiltZ for angled limbs
                    val wx = r.cx + lx * cosT
                    val wy = r.cy + lx * sinT
                    val wz = r.cz + lz
                    verts += Vec3(wx, wy, wz)
                }
            }

            // Connect consecutive rings with outward-facing CCW triangles
            for (rIdx in 0 until rings.size - 1) {
                val base0 = ringStartIndices[rIdx]
                val base1 = ringStartIndices[rIdx + 1]
                val isContour = rings[rIdx].contour
                for (s in 0 until radialSteps) {
                    val sn = (s + 1) % radialSteps
                    val a = base0 + s
                    val b = base0 + sn
                    val c = base1 + sn
                    val d = base1 + s
                    // Outward winding verified against ring center:
                    // When cy increases (bottom->top), (a, c, b) and (a, d, c) point outward;
                    // when cy decreases (top->bottom), swap winding so normals always point outward.
                    val ascending = rings[rIdx + 1].cy >= rings[rIdx].cy
                    if (ascending) {
                        tris += Triangle(a, c, b, segmentId, contourEdge = isContour)
                        tris += Triangle(a, d, c, segmentId, contourEdge = false)
                    } else {
                        tris += Triangle(a, b, c, segmentId, contourEdge = isContour)
                        tris += Triangle(a, c, d, segmentId, contourEdge = false)
                    }
                }
            }

            if (capStart) {
                val r0 = rings.first()
                val centerIdx = verts.size
                verts += Vec3(r0.cx, r0.cy, r0.cz)
                val base = ringStartIndices.first()
                val ascending = rings[1].cy >= rings[0].cy
                for (s in 0 until radialSteps) {
                    val sn = (s + 1) % radialSteps
                    if (ascending) {
                        tris += Triangle(centerIdx, base + s, base + sn, segmentId)
                    } else {
                        tris += Triangle(centerIdx, base + sn, base + s, segmentId)
                    }
                }
            }
            if (capEnd) {
                val rN = rings.last()
                val centerIdx = verts.size
                verts += Vec3(rN.cx, rN.cy, rN.cz)
                val base = ringStartIndices.last()
                val ascending = rings[rings.size - 1].cy >= rings[rings.size - 2].cy
                for (s in 0 until radialSteps) {
                    val sn = (s + 1) % radialSteps
                    if (ascending) {
                        tris += Triangle(centerIdx, base + sn, base + s, segmentId)
                    } else {
                        tris += Triangle(centerIdx, base + s, base + sn, segmentId)
                    }
                }
            }
        }
    }

    /** Builds the complete morphed 3D human body mesh from [input]. */
    fun buildMesh(input: MorphInput): Mesh {
        val f = computeMorphFactors(input)
        val mb = MeshBuilder()

        val fem = f.isFemale
        val trunkFatExcess = max(0f, f.trunkFatScale - 0.94f)
        val legFatAvg = (f.rightLegFat + f.leftLegFat) * 0.5f
        val legFatExcess = max(0f, legFatAvg - 0.94f)
        val vfExcess = max(0f, f.visceralNorm + 0.05f)

        // Sex-specific fat depot curves driven by measured fat & visceral fat
        // Male (android): anterior + lateral abdomen (belly & love handles) + mild lower chest
        val androidBelly = if (fem) 0f else (trunkFatExcess * 0.092f + vfExcess * 0.062f).coerceAtMost(0.094f)
        val loveHandleX = if (fem) 0f else (trunkFatExcess * 0.026f + vfExcess * 0.016f).coerceAtMost(0.030f)
        val maleLowerChestFat = if (fem) 0f else (trunkFatExcess * 0.020f).coerceAtMost(0.024f)

        // Female (gynoid): hips, glutes, upper outer thighs, soft lower-belly pouch, bust fullness & triceps
        val gynoidHipExtra = if (fem) (legFatExcess * 0.026f + trunkFatExcess * 0.014f).coerceAtMost(0.034f) else 0f
        val gynoidLowerBelly = if (fem) (trunkFatExcess * 0.048f + vfExcess * 0.044f).coerceAtMost(0.062f) else 0f
        val gynoidUpperBelly = if (fem) (vfExcess * 0.042f + trunkFatExcess * 0.015f).coerceAtMost(0.048f) else 0f

        val sShoulder = (if (fem) 0.192f else 0.264f) * f.shoulderScale
        val sChestX = (if (fem) 0.148f else 0.206f) * (0.65f * f.shoulderScale + 0.35f * f.waistWidthScale)
        val sUnderBustX = (if (fem) 0.126f else 0.184f) * (0.50f * f.shoulderScale + 0.50f * f.waistWidthScale)
        val sChestZ = (if (fem) 0.096f else 0.130f) * f.chestDepthScale
        val sWaistX = (if (fem) 0.112f else 0.158f) * f.waistWidthScale
        val sWaistZ = (if (fem) 0.082f else 0.112f) * (if (fem) (0.52f + 0.48f * f.bellyDepthScale) else f.bellyDepthScale)
        val sHipX = (if (fem) 0.190f else 0.172f) * f.hipWidthScale + gynoidHipExtra
        val sHipZ = (if (fem) 0.116f else 0.114f) * (0.60f * f.hipWidthScale + 0.40f * f.bellyDepthScale)

        val malePecBulge = if (fem) 0f else 0.022f * f.chestDepthScale + maleLowerChestFat * 0.55f
        val femaleBust = if (fem) {
            ((0.046f + trunkFatExcess * 0.020f) * (0.60f * f.chestDepthScale + 0.40f * f.hipWidthScale.coerceAtMost(1.25f)))
                .coerceIn(0.034f, 0.072f)
        } else 0f
        val gluteBulge = if (fem) {
            ((0.038f + legFatExcess * 0.024f + trunkFatExcess * 0.010f) * f.hipWidthScale).coerceAtMost(0.056f)
        } else {
            ((0.022f + legFatExcess * 0.008f) * f.hipWidthScale).coerceAtMost(0.034f)
        }
        val trunkGroove = clamp((if (fem) 0.06f else 0.11f) - trunkFatExcess * 0.14f, 0.02f, 0.12f)

        // 1. TORSO (SEG_TRUNK = 12) — distinct female skeletal frame (slender ribcage, inframammary taper,
        //    high natural waist, lumbar lordosis S-curve, wide sweeping pelvis) vs male V-taper frame.
        val torsoRings = if (fem) {
            listOf(
                RingSpec(0f, -0.095f, -0.010f, sHipX * 0.36f, sHipZ * 0.54f, backBulge = gluteBulge * 0.38f),
                RingSpec(0f, -0.055f, -0.012f, sHipX * 0.87f, sHipZ * 0.89f, backBulge = gluteBulge * 0.88f),
                RingSpec(0f, -0.008f, -0.012f, sHipX * 0.99f, sHipZ * 0.98f, frontBulge = gynoidLowerBelly * 0.45f, backBulge = gluteBulge * 0.98f, contour = true),
                RingSpec(0f, 0.045f, -0.007f, sHipX * 0.92f, sHipZ * 0.94f, frontBulge = gynoidLowerBelly * 0.88f, backBulge = gluteBulge * 0.78f),
                RingSpec(0f, 0.102f, 0.003f, (sWaistX * 0.58f + sHipX * 0.42f), sWaistZ * 1.05f, frontBulge = gynoidLowerBelly * 0.96f, backBulge = gluteBulge * 0.28f, medianGroove = trunkGroove * 0.30f),
                RingSpec(0f, 0.162f, 0.011f, sWaistX * 1.04f, sWaistZ * 0.98f, frontBulge = gynoidLowerBelly * 0.62f + gynoidUpperBelly * 0.65f, medianGroove = trunkGroove * 0.45f, contour = true),
                RingSpec(0f, 0.220f, 0.011f, sWaistX * 0.96f, sWaistZ * 0.94f, frontBulge = gynoidUpperBelly * 0.78f, medianGroove = trunkGroove * 0.55f), // high slender female waist
                RingSpec(0f, 0.278f, 0.006f, sUnderBustX, sChestZ * 0.90f, frontBulge = gynoidUpperBelly * 0.38f, bustBulge = femaleBust * 0.14f, medianGroove = trunkGroove * 0.65f), // inframammary ribcage taper
                RingSpec(0f, 0.326f, 0.003f, (sUnderBustX * 0.35f + sChestX * 0.65f), sChestZ * 0.96f, bustBulge = femaleBust * 0.82f, backBulge = 0.006f, medianGroove = trunkGroove * 0.75f, contour = true),
                RingSpec(0f, 0.368f, 0.002f, sChestX * 1.00f, sChestZ * 0.99f, bustBulge = femaleBust * 1.08f, backBulge = 0.010f, medianGroove = trunkGroove * 0.80f), // bust apex
                RingSpec(0f, 0.418f, -0.002f, sShoulder * 0.92f, sChestZ * 0.92f, bustBulge = femaleBust * 0.42f, backBulge = 0.012f, medianGroove = trunkGroove * 0.55f, contour = true),
                RingSpec(0f, 0.472f, -0.005f, sShoulder * 0.88f, sChestZ * 0.80f, backBulge = 0.009f),
                RingSpec(0f, 0.514f, -0.007f, sShoulder * 0.68f, sChestZ * 0.64f, backBulge = 0.005f), // softly sloped female clavicles/traps
                RingSpec(0f, 0.554f, -0.008f, 0.074f * f.shoulderScale.coerceAtMost(1.10f), 0.056f),
                RingSpec(0f, 0.588f, -0.007f, 0.049f, 0.048f),
            )
        } else {
            listOf(
                RingSpec(0f, -0.095f, -0.006f, sHipX * 0.36f, sHipZ * 0.54f, backBulge = gluteBulge * 0.35f),
                RingSpec(0f, -0.055f, -0.005f, sHipX * 0.80f, sHipZ * 0.88f, backBulge = gluteBulge * 0.82f),
                RingSpec(0f, -0.005f, -0.004f, sHipX * 0.99f, sHipZ * 0.98f, frontBulge = androidBelly * 0.36f, backBulge = gluteBulge * 0.94f, contour = true),
                RingSpec(0f, 0.048f, -0.002f, sHipX * 0.96f + loveHandleX * 0.62f, sHipZ * 0.97f, frontBulge = androidBelly * 0.74f, backBulge = gluteBulge * 0.66f),
                RingSpec(0f, 0.102f, 0.002f, sWaistX * 1.04f + loveHandleX * 0.96f, sWaistZ * 1.04f, frontBulge = androidBelly * 0.96f, backBulge = gluteBulge * 0.24f, medianGroove = trunkGroove * 0.35f),
                RingSpec(0f, 0.158f, 0.004f, sWaistX * 1.00f + loveHandleX * 1.00f, sWaistZ * 1.04f, frontBulge = androidBelly * 1.00f, medianGroove = trunkGroove * 0.50f, contour = true), // male central android belly peak
                RingSpec(0f, 0.215f, 0.004f, sWaistX * 1.00f + loveHandleX * 0.72f, sWaistZ * 1.00f, frontBulge = androidBelly * 0.88f, medianGroove = trunkGroove * 0.65f),
                RingSpec(0f, 0.270f, 0.003f, sUnderBustX * 0.95f + loveHandleX * 0.32f, sChestZ * 0.96f, frontBulge = androidBelly * 0.56f + maleLowerChestFat * 0.50f, medianGroove = trunkGroove * 0.80f),
                RingSpec(0f, 0.322f, 0.003f, (sWaistX * 0.34f + sChestX * 0.66f), sChestZ * 0.98f, frontBulge = androidBelly * 0.24f + malePecBulge * 0.60f + maleLowerChestFat * 0.65f, backBulge = 0.008f, medianGroove = trunkGroove * 0.90f, contour = true),
                RingSpec(0f, 0.372f, 0.005f, sChestX * 1.02f, sChestZ * 1.02f, frontBulge = malePecBulge * 1.00f, backBulge = 0.015f, medianGroove = trunkGroove),
                RingSpec(0f, 0.425f, 0.003f, sShoulder * 0.96f, sChestZ * 0.96f, frontBulge = malePecBulge * 0.78f, backBulge = 0.019f, medianGroove = trunkGroove * 0.82f, contour = true),
                RingSpec(0f, 0.482f, 0.000f, sShoulder * 0.93f, sChestZ * 0.85f, frontBulge = malePecBulge * 0.35f, backBulge = 0.014f, medianGroove = trunkGroove * 0.45f),
                RingSpec(0f, 0.526f, -0.004f, sShoulder * 0.77f, sChestZ * 0.69f, backBulge = 0.008f),
                RingSpec(0f, 0.566f, -0.006f, 0.112f * f.shoulderScale.coerceAtMost(1.12f), 0.074f),
                RingSpec(0f, 0.594f, -0.006f, 0.075f, 0.066f),
            )
        }
        mb.addLoft(torsoRings, radialSteps = 24, segmentId = SEG_TRUNK, capStart = true, capEnd = true)

        // 2. NECK & HEAD (SEG_NEUTRAL = 0) — distinct female (slender longer neck, softer narrower jaw) vs male (stronger neck/jaw)
        val neckR = if (fem) 0.042f else 0.059f
        val headScale = if (fem) 0.92f else 1.00f
        val neckHeadRings = listOf(
            RingSpec(0f, if (fem) 0.568f else 0.575f, -0.006f, neckR * 1.16f, neckR * 1.08f),
            RingSpec(0f, 0.635f, -0.003f, neckR, neckR * 1.02f, contour = true),
            RingSpec(0f, 0.692f, 0.004f, neckR * 0.96f, neckR * 1.10f),
            RingSpec(0f, 0.720f, 0.014f, (if (fem) 0.048f else 0.068f), 0.076f * headScale, frontBulge = if (fem) 0.012f else 0.015f), // chin / jaw
            RingSpec(0f, 0.768f, 0.011f, (if (fem) 0.078f else 0.085f), 0.098f * headScale, frontBulge = 0.012f, contour = true), // cheekbones
            RingSpec(0f, 0.830f, 0.004f, 0.093f * headScale, 0.109f * headScale, frontBulge = 0.008f), // brow / temples
            RingSpec(0f, 0.892f, -0.002f, 0.086f * headScale, 0.099f * headScale, contour = true), // upper cranium
            RingSpec(0f, 0.938f, -0.004f, 0.062f * headScale, 0.072f * headScale),
            RingSpec(0f, 0.964f, -0.004f, 0.024f * headScale, 0.028f * headScale),
        )
        mb.addLoft(neckHeadRings, radialSteps = 20, segmentId = SEG_NEUTRAL, capStart = true, capEnd = true)

        // 3. ARMS (SEG_RIGHT_ARM = 2 at -X, SEG_LEFT_ARM = 3 at +X)
        fun addArm(sideSign: Float, segId: Int, mScale: Float, fScale: Float): Vec3 {
            val baseGirth = (if (fem) 0.040f else 0.057f)
            val upperR = baseGirth * (if (fem) (0.54f * mScale + 0.46f * fScale) else (0.64f * mScale + 0.36f * fScale))
            val deltoidR = baseGirth * (if (fem) 1.02f else 1.20f) * (0.72f * mScale + 0.28f * fScale)
            val elbowR = baseGirth * (if (fem) 0.72f else 0.77f) * (0.45f * mScale + 0.55f * fScale)
            val forearmR = baseGirth * (if (fem) 0.72f else 0.86f) * (0.70f * mScale + 0.30f * fScale)
            val wristR = baseGirth * (if (fem) 0.46f else 0.53f) * (0.40f * mScale + 0.60f * fScale)
            val sx = sideSign * max(sShoulder * (if (fem) 0.83f else 0.86f), (sWaistX + loveHandleX) * 0.92f)
            val clearanceBoost = if (fem) (gynoidHipExtra * 1.6f) else (loveHandleX * 2.0f)
            val tilt = sideSign * ((if (fem) 0.26f else 0.20f) + clearanceBoost) // arms stay gracefully clear of fuller flanks/hips
            val tricepsSoftness = (if (fem) 0.011f else 0.006f) * fScale

            val rings = listOf(
                // Rounded deltoid cap tucked cleanly inside the shoulder socket
                RingSpec(sx * 0.72f, if (fem) 0.492f else 0.504f, -0.006f, deltoidR * 0.54f, deltoidR * 0.60f, tiltZ = tilt * 1.3f),
                RingSpec(sx * 0.90f, if (fem) 0.474f else 0.486f, -0.005f, deltoidR * 0.90f, deltoidR * 0.94f, tiltZ = tilt * 1.1f),
                RingSpec(sx * 1.04f, if (fem) 0.436f else 0.445f, -0.004f, deltoidR * 1.00f, deltoidR * 1.02f, tiltZ = tilt, contour = true),
                RingSpec(sx * 1.15f, 0.360f, -0.001f, upperR * 1.04f, upperR * 1.10f, frontBulge = (if (fem) 0.005f else 0.009f) * mScale, backBulge = tricepsSoftness, tiltZ = tilt),
                RingSpec(sx * 1.24f, 0.268f, 0.005f, upperR * 0.90f, upperR * 0.95f, backBulge = tricepsSoftness * 0.65f, tiltZ = tilt, contour = true),
                RingSpec(sx * 1.32f, 0.188f, 0.013f, elbowR, elbowR * 1.02f, tiltZ = tilt * 0.8f),
                RingSpec(sx * 1.40f, 0.098f, 0.025f, forearmR, forearmR * 1.04f, tiltZ = tilt * 0.6f, contour = true),
                RingSpec(sx * 1.47f, 0.000f, 0.035f, forearmR * 0.76f, forearmR * 0.78f, tiltZ = tilt * 0.5f),
                RingSpec(sx * 1.52f, -0.085f, 0.041f, wristR, wristR * 0.84f, tiltZ = tilt * 0.4f),
                // Sculpted palm / hand silhouette
                RingSpec(sx * 1.54f, -0.155f, 0.045f, wristR * 1.08f, wristR * 0.56f, tiltZ = tilt * 0.3f),
                RingSpec(sx * 1.55f, -0.218f, 0.047f, wristR * 0.60f, wristR * 0.32f, tiltZ = tilt * 0.2f),
            )
            mb.addLoft(rings, radialSteps = 16, segmentId = segId, capStart = true, capEnd = true)
            // Callout anchor seated right on outer mid-biceps surface
            return Vec3(sx * 1.18f + sideSign * upperR * 0.72f, 0.325f, 0.004f)
        }

        val rightArmAnchor = addArm(-1f, SEG_RIGHT_ARM, f.rightArmMuscle, f.rightArmFat)
        val leftArmAnchor = addArm(+1f, SEG_LEFT_ARM, f.leftArmMuscle, f.leftArmFat)

        // 4. LEGS (SEG_RIGHT_LEG = 10 at -X, SEG_LEFT_LEG = 11 at +X)
        fun addLeg(sideSign: Float, segId: Int, mScale: Float, fScale: Float): Vec3 {
            val baseThigh = (if (fem) 0.096f else 0.093f)
            val hipX = sideSign * (sHipX * (if (fem) 0.485f else 0.52f))
            val kneeX = sideSign * (sHipX * (if (fem) 0.41f else 0.59f))
            val ankleX = sideSign * (sHipX * (if (fem) 0.39f else 0.63f))

            // Female upper thigh grows more strongly with gynoid leg fat; male thigh stays more quad/muscle driven
            val upperThighR = if (fem) {
                baseThigh * (0.40f * mScale + 0.60f * fScale) + gynoidHipExtra * 0.38f
            } else {
                baseThigh * (0.58f * mScale + 0.42f * fScale)
            }
            val quadR = baseThigh * (if (fem) 0.84f else 0.92f) * (if (fem) (0.55f * mScale + 0.45f * fScale) else (0.70f * mScale + 0.30f * fScale))
            val kneeR = baseThigh * (if (fem) 0.52f else 0.63f) * (0.45f * mScale + 0.55f * fScale)
            val calfR = baseThigh * (if (fem) 0.57f else 0.70f) * (0.70f * mScale + 0.30f * fScale)
            val ankleR = baseThigh * (if (fem) 0.33f else 0.43f) * (0.35f * mScale + 0.65f * fScale)

            val rings = listOf(
                // Rounded hip socket seamlessly nested inside the pelvic volume
                RingSpec(hipX * 0.90f, 0.042f, if (fem) -0.010f else -0.005f, upperThighR * 0.82f, upperThighR * 0.86f, backBulge = gluteBulge * 0.52f),
                RingSpec(hipX * 0.99f, -0.015f, if (fem) -0.011f else -0.004f, upperThighR * 1.00f, upperThighR * 1.00f, backBulge = gluteBulge * 0.78f),
                RingSpec(hipX * 1.01f, -0.095f, if (fem) -0.004f else -0.001f, upperThighR * 1.01f, upperThighR * 1.02f, frontBulge = 0.011f * mScale, backBulge = gluteBulge * 0.48f + (if (fem) 0.016f else 0.010f) * fScale, contour = true),
                RingSpec((hipX * 0.64f + kneeX * 0.36f), -0.225f, 0.005f, quadR * 0.98f, quadR * 1.01f, frontBulge = (if (fem) 0.012f else 0.017f) * mScale, backBulge = (if (fem) 0.012f else 0.005f) * fScale),
                RingSpec((hipX * 0.28f + kneeX * 0.72f), -0.355f, 0.005f, quadR * 0.79f, quadR * 0.81f, frontBulge = 0.007f * mScale, contour = true),
                RingSpec(kneeX, -0.465f, 0.002f, kneeR, kneeR * 1.01f),
                RingSpec((kneeX * 0.75f + ankleX * 0.25f), -0.575f, -0.006f, calfR * 0.95f, calfR * 1.03f, backBulge = (if (fem) 0.014f else 0.019f) * mScale, contour = true),
                RingSpec((kneeX * 0.45f + ankleX * 0.55f), -0.695f, -0.008f, calfR * 0.82f, calfR * 0.88f, backBulge = (if (fem) 0.010f else 0.013f) * mScale),
                RingSpec(ankleX, -0.835f, -0.004f, ankleR * 1.06f, ankleR * 1.10f, contour = true),
                RingSpec(ankleX, -0.920f, 0.000f, ankleR * 0.92f, ankleR * 0.98f),
                // Foot silhouette resting on pedestal (y = -1.00)
                RingSpec(ankleX * 1.02f, -0.968f, if (fem) 0.034f else 0.040f, ankleR * 1.02f, ankleR * (if (fem) 1.72f else 1.82f), frontBulge = if (fem) 0.018f else 0.022f),
                RingSpec(ankleX * 1.03f, -0.998f, if (fem) 0.043f else 0.050f, ankleR * 0.98f, ankleR * (if (fem) 1.82f else 1.92f), frontBulge = if (fem) 0.013f else 0.016f),
            )
            mb.addLoft(rings, radialSteps = 16, segmentId = segId, capStart = true, capEnd = true)
            // Callout anchor seated right on outer mid-thigh surface
            return Vec3((hipX * 0.65f + kneeX * 0.35f) + sideSign * quadR * 0.74f, -0.255f, 0.012f)
        }

        val rightLegAnchor = addLeg(-1f, SEG_RIGHT_LEG, f.rightLegMuscle, f.rightLegFat)
        val leftLegAnchor = addLeg(+1f, SEG_LEFT_LEG, f.leftLegMuscle, f.leftLegFat)
        val trunkAnchor = Vec3(0f, 0.305f, sChestZ + 0.015f)
        val visceralCenter = Vec3(0f, 0.165f, 0.025f)

        // Apply subtle overall scale symmetrically around center
        val finalVerts = if (abs(f.overallScale - 1f) > 1e-4f) {
            mb.verts.map { Vec3(it.x * f.overallScale, it.y, it.z * f.overallScale) }
        } else mb.verts

        // Compute smooth vertex normals by area-weighted face normal averaging (kept within same segment so seams stay crisp)
        val accum = Array(finalVerts.size) { FloatArray(3) }
        for (tri in mb.tris) {
            val v0 = finalVerts[tri.i0]
            val v1 = finalVerts[tri.i1]
            val v2 = finalVerts[tri.i2]
            val n = (v1 - v0).cross(v2 - v0)
            for (idx in intArrayOf(tri.i0, tri.i1, tri.i2)) {
                accum[idx][0] += n.x
                accum[idx][1] += n.y
                accum[idx][2] += n.z
            }
        }
        val vNormals = List(finalVerts.size) { i ->
            val a = accum[i]
            Vec3(a[0], a[1], a[2]).normalized()
        }

        val anchors = mapOf(
            SEG_RIGHT_ARM to Vec3(rightArmAnchor.x * f.overallScale, rightArmAnchor.y, rightArmAnchor.z * f.overallScale),
            SEG_LEFT_ARM to Vec3(leftArmAnchor.x * f.overallScale, leftArmAnchor.y, leftArmAnchor.z * f.overallScale),
            SEG_TRUNK to Vec3(trunkAnchor.x * f.overallScale, trunkAnchor.y, trunkAnchor.z * f.overallScale),
            SEG_RIGHT_LEG to Vec3(rightLegAnchor.x * f.overallScale, rightLegAnchor.y, rightLegAnchor.z * f.overallScale),
            SEG_LEFT_LEG to Vec3(leftLegAnchor.x * f.overallScale, leftLegAnchor.y, leftLegAnchor.z * f.overallScale),
        )

        return Mesh(
            vertices = finalVerts,
            vertexNormals = vNormals,
            triangles = mb.tris,
            anchors = anchors,
            visceralCenter = visceralCenter,
            factors = f,
        )
    }

    /**
     * Projects [mesh] into 2D viewport coordinates with perspective camera, yaw/pitch rotation,
     * back-face culling, smooth 3-point + Fresnel lighting, and painter's Z-sort.
     *
     * Pass a remembered [ProjectionScratch] from interactive UI to avoid per-frame primitive array allocations.
     */
    fun project(
        mesh: Mesh,
        yawRad: Float,
        pitchRad: Float = -0.06f,
        viewportWidth: Float,
        viewportHeight: Float,
        cameraDist: Float = 3.45f,
        scratch: ProjectionScratch? = null,
    ): ProjectedScene {
        val cy = cos(yawRad)
        val sy = sin(yawRad)
        val cp = cos(pitchRad)
        val sp = sin(pitchRad)

        val focal = min(viewportWidth * 1.18f, viewportHeight * 0.445f) * (cameraDist - 0.1f)
        val cx = viewportWidth * 0.5f
        val cyScreen = viewportHeight * 0.50f

        val verts = mesh.vertices
        val normals = mesh.vertexNormals
        val nVerts = verts.size
        val buf = scratch ?: ProjectionScratch()
        buf.ensureCapacity(nVerts)
        val rotVx = buf.rotVx
        val rotVy = buf.rotVy
        val rotVz = buf.rotVz
        val rotNx = buf.rotNx
        val rotNy = buf.rotNy
        val rotNz = buf.rotNz
        val sx = buf.sx
        val syArr = buf.sy

        for (i in 0 until nVerts) {
            val v = verts[i]
            val rx = v.x * cy + v.z * sy
            val rz1 = -v.x * sy + v.z * cy
            val ry = v.y * cp - rz1 * sp
            val rz = v.y * sp + rz1 * cp
            rotVx[i] = rx
            rotVy[i] = ry
            rotVz[i] = rz

            val n = normals[i]
            val nrx = n.x * cy + n.z * sy
            val nrz1 = -n.x * sy + n.z * cy
            rotNx[i] = nrx
            rotNy[i] = n.y * cp - nrz1 * sp
            rotNz[i] = n.y * sp + nrz1 * cp

            val zCam = (cameraDist - rz).coerceAtLeast(0.4f)
            val invZ = focal / zCam
            sx[i] = cx + rx * invZ
            syArr[i] = cyScreen - ry * invZ
        }

        // Pre-normalized 3-point studio lighting in camera space (no per-frame Vec3 allocations)
        val keyLen = sqrt(0.42f * 0.42f + 0.58f * 0.58f + 0.70f * 0.70f)
        val klx = -0.42f / keyLen; val kly = 0.58f / keyLen; val klz = 0.70f / keyLen
        val fillLen = sqrt(0.68f * 0.68f + 0.22f * 0.22f + 0.35f * 0.35f)
        val flx = 0.68f / fillLen; val fly = 0.22f / fillLen; val flz = 0.35f / fillLen
        val hxRaw = klx; val hyRaw = kly; val hzRaw = klz + 1f
        val hLen = sqrt(hxRaw * hxRaw + hyRaw * hyRaw + hzRaw * hzRaw)
        val hx = hxRaw / hLen; val hy = hyRaw / hLen; val hz = hzRaw / hLen

        val tris = mesh.triangles
        val projected = ArrayList<ProjectedTri>(tris.size / 2 + 64)
        for (idx in tris.indices) {
            val tri = tris[idx]
            val i0 = tri.i0
            val i1 = tri.i1
            val i2 = tri.i2

            val x0 = sx[i0]; val y0 = syArr[i0]
            val x1 = sx[i1]; val y1 = syArr[i1]
            val x2 = sx[i2]; val y2 = syArr[i2]

            // 2D signed screen area for fast back-face culling
            val cross2d = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
            if (cross2d >= 0f) continue

            // Smooth interpolated normal at triangle centroid (primitive math, zero allocations)
            val anx = rotNx[i0] + rotNx[i1] + rotNx[i2]
            val any = rotNy[i0] + rotNy[i1] + rotNy[i2]
            val anz = rotNz[i0] + rotNz[i1] + rotNz[i2]
            val aLen = sqrt(anx * anx + any * any + anz * anz)
            val invALen = if (aLen > 1e-6f) 1f / aLen else 0f
            val nx = anx * invALen
            val ny = any * invALen
            val nz = anz * invALen

            val diffuse = clamp(nx * klx + ny * kly + nz * klz, 0f, 1f)
            val fill = clamp(nx * flx + ny * fly + nz * flz, 0f, 1f)
            val ndotv = clamp(abs(nz), 0f, 1f)
            val oneMinus = 1f - ndotv
            // Fast approximate pow(2.2) = sq * sqrt(sqrt) avoids heavy Math.pow inside inner loop
            val fresnel = oneMinus * oneMinus * sqrt(sqrt(oneMinus.coerceAtLeast(0f)))
            val specDot = clamp(nx * hx + ny * hy + nz * hz, 0f, 1f)
            val s2 = specDot * specDot
            val s4 = s2 * s2
            val s8 = s4 * s4
            val spec = s8 * s8 // exact pow(16) using 4 multiplications!

            val depth = (rotVz[i0] + rotVz[i1] + rotVz[i2]) * (1f / 3f)
            projected += ProjectedTri(
                x0 = x0, y0 = y0,
                x1 = x1, y1 = y1,
                x2 = x2, y2 = y2,
                depth = depth,
                segmentId = tri.segmentId,
                diffuse = diffuse,
                fill = fill,
                fresnel = fresnel,
                specular = spec,
                contourEdge = tri.contourEdge,
            )
        }
        // Painter's algorithm: draw farthest (-z) to nearest (+z)
        projected.sortBy { it.depth }

        val projAnchors = LinkedHashMap<Int, ProjectedAnchor>(8)
        for ((segId, a) in mesh.anchors) {
            val rx = a.x * cy + a.z * sy
            val rz1 = -a.x * sy + a.z * cy
            val ry = a.y * cp - rz1 * sp
            val rz = a.y * sp + rz1 * cp
            val zCam = (cameraDist - rz).coerceAtLeast(0.4f)
            val invZ = focal / zCam
            projAnchors[segId] = ProjectedAnchor(
                segmentId = segId,
                x = cx + rx * invZ,
                y = cyScreen - ry * invZ,
                depth = rz,
                frontFacing = rz >= -0.05f,
            )
        }

        val vc = mesh.visceralCenter
        val vrx = vc.x * cy + vc.z * sy
        val vrz1 = -vc.x * sy + vc.z * cy
        val vry = vc.y * cp - vrz1 * sp
        val vrz = vc.y * sp + vrz1 * cp
        val vzCam = (cameraDist - vrz).coerceAtLeast(0.4f)
        val vInvZ = focal / vzCam

        return ProjectedScene(
            triangles = projected,
            anchors = projAnchors,
            visceralCenterX = cx + vrx * vInvZ,
            visceralCenterY = cyScreen - vry * vInvZ,
            visceralDepth = vrz,
            visceralScreenRadius = mesh.factors.visceralCoreRadius * vInvZ,
        )
    }

    /** Projects any arbitrary 3D world point (e.g. pedestal ring vertices) using the exact same camera. */
    fun projectPoint(
        point: Vec3,
        yawRad: Float,
        pitchRad: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        cameraDist: Float = 3.45f,
    ): Pair<Float, Float> {
        val cy = cos(yawRad)
        val sy = sin(yawRad)
        val cp = cos(pitchRad)
        val sp = sin(pitchRad)
        val rx = point.x * cy + point.z * sy
        val rz1 = -point.x * sy + point.z * cy
        val ry = point.y * cp - rz1 * sp
        val rz = point.y * sp + rz1 * cp
        val focal = min(viewportWidth * 1.18f, viewportHeight * 0.445f) * (cameraDist - 0.1f)
        val zCam = (cameraDist - rz).coerceAtLeast(0.4f)
        val invZ = focal / zCam
        return (viewportWidth * 0.5f + rx * invZ) to (viewportHeight * 0.50f - ry * invZ)
    }

    /**
     * Hit-tests a tap at screen coordinates ([tapX], [tapY]) against the front-most projected measured segment.
     * Returns Withings segment position ID (`2, 3, 10, 11, 12`) or `null` if tapped on background / neutral anatomy.
     */
    fun hitTestSegment(scene: ProjectedScene, tapX: Float, tapY: Float): Int? {
        // Iterate nearest-to-farthest (reverse of painter's order)
        for (i in scene.triangles.lastIndex downTo 0) {
            val t = scene.triangles[i]
            if (t.segmentId == SEG_NEUTRAL) continue
            if (pointInTriangle(tapX, tapY, t.x0, t.y0, t.x1, t.y1, t.x2, t.y2)) {
                return t.segmentId
            }
        }
        return null
    }

    private fun pointInTriangle(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
        cx: Float, cy: Float,
    ): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val hasNeg = (d1 < 0f) || (d2 < 0f) || (d3 < 0f)
        val hasPos = (d1 > 0f) || (d2 > 0f) || (d3 > 0f)
        return !(hasNeg && hasPos)
    }
}
