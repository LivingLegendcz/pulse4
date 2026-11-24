package app.pulse4.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Body3DModelTest {

    private fun athleticInput() = Body3DModel.MorphInput(
        muscleByPos = mapOf(2 to 4.9, 3 to 4.8, 12 to 38.0, 10 to 13.5, 11 to 13.4),
        fatByPos = mapOf(2 to 0.6, 3 to 0.6, 12 to 6.5, 10 to 2.0, 11 to 2.0),
        ffmByPos = mapOf(2 to 5.2, 3 to 5.1, 12 to 40.0, 10 to 14.3, 11 to 14.2),
        totalWeightKg = 86.0,
        visceralFatIdx = 2.0,
        gender = 0,
    )

    private fun higherFatInput() = Body3DModel.MorphInput(
        muscleByPos = mapOf(2 to 3.4, 3 to 3.4, 12 to 29.0, 10 to 10.2, 11 to 10.2),
        fatByPos = mapOf(2 to 1.8, 3 to 1.8, 12 to 20.5, 10 to 5.4, 11 to 5.4),
        ffmByPos = mapOf(2 to 3.6, 3 to 3.6, 12 to 30.8, 10 to 10.8, 11 to 10.8),
        totalWeightKg = 104.0,
        visceralFatIdx = 7.5,
        gender = 0,
    )

    @Test
    fun morphingShapesTorsoAndBellyFromTrunkFatAndVisceralFat() {
        val lean = Body3DModel.computeMorphFactors(athleticInput())
        val fuller = Body3DModel.computeMorphFactors(higherFatInput())

        assertTrue(fuller.waistWidthScale > lean.waistWidthScale, "Higher trunk fat must widen waist")
        assertTrue(fuller.bellyDepthScale > lean.bellyDepthScale, "Higher trunk + visceral fat must deepen belly")
        assertTrue(lean.shoulderScale > fuller.shoulderScale, "Higher trunk muscle must broaden shoulders")
        assertTrue(fuller.visceralCoreRadius > lean.visceralCoreRadius, "Higher visceral fat must grow core radius")
    }

    @Test
    fun morphingReflectsLeftRightLimbAsymmetryInActual3DMeshGeometry() {
        val asymmetric = Body3DModel.MorphInput(
            muscleByPos = mapOf(2 to 5.6, 3 to 3.1, 12 to 34.0, 10 to 14.0, 11 to 10.0),
            fatByPos = mapOf(2 to 1.0, 3 to 0.8, 12 to 10.0, 10 to 3.0, 11 to 2.5),
            totalWeightKg = 85.0,
            gender = 0,
        )
        val mesh = Body3DModel.buildMesh(asymmetric)
        assertTrue(mesh.factors.rightArmMuscle > mesh.factors.leftArmMuscle)
        assertTrue(mesh.factors.rightLegMuscle > mesh.factors.leftLegMuscle)

        // Compare actual Z-depth span of right arm triangles vs left arm triangles in the generated 3D mesh
        fun zSpan(segId: Int): Float {
            val zs = mesh.triangles.filter { it.segmentId == segId }
                .flatMap { listOf(mesh.vertices[it.i0].z, mesh.vertices[it.i1].z, mesh.vertices[it.i2].z) }
            return zs.max() - zs.min()
        }
        assertTrue(zSpan(Body3DModel.SEG_RIGHT_ARM) > zSpan(Body3DModel.SEG_LEFT_ARM))
        assertTrue(zSpan(Body3DModel.SEG_RIGHT_LEG) > zSpan(Body3DModel.SEG_LEFT_LEG))
    }

    @Test
    fun buildMeshGeneratesFiniteGeometryAndAllFiveMeasuredSegmentAnchors() {
        val mesh = Body3DModel.buildMesh(athleticInput())
        assertTrue(mesh.vertices.size > 600, "Expected rich anatomical mesh")
        assertTrue(mesh.triangles.size > 1000, "Expected >1000 triangles")
        assertTrue(mesh.vertices.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        assertTrue(mesh.vertexNormals.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })

        for (seg in Body3DModel.MEASURED_SEGMENTS) {
            assertNotNull(mesh.anchors[seg], "Missing 3D anchor for segment $seg")
            assertTrue(mesh.triangles.any { it.segmentId == seg }, "Missing triangles for segment $seg")
        }
    }

    @Test
    fun projectionAcrossFull360RotationProducesSortedFrontFacesAndHitTesting() {
        val mesh = Body3DModel.buildMesh(athleticInput())
        for (deg in listOf(0, 45, 90, 180, 270)) {
            val scene = Body3DModel.project(
                mesh = mesh,
                yawRad = (deg * PI / 180.0).toFloat(),
                viewportWidth = 1000f,
                viewportHeight = 1000f,
            )
            assertTrue(scene.triangles.size > 300, "Expected visible triangles at $deg deg")
            // Painter's order: non-decreasing depth
            for (i in 1 until scene.triangles.size) {
                assertTrue(scene.triangles[i].depth >= scene.triangles[i - 1].depth)
            }
            assertTrue(scene.visceralScreenRadius > 0f && scene.visceralScreenRadius.isFinite())
        }

        // Front view hit-testing at trunk anchor must hit SEG_TRUNK; far corner hits null
        val front = Body3DModel.project(mesh, yawRad = 0f, viewportWidth = 1000f, viewportHeight = 1000f)
        val trunkAnchor = front.anchors.getValue(Body3DModel.SEG_TRUNK)
        assertEquals(Body3DModel.SEG_TRUNK, Body3DModel.hitTestSegment(front, trunkAnchor.x, trunkAnchor.y))
        assertNull(Body3DModel.hitTestSegment(front, 5f, 5f))

        // At yaw = 0, person's right arm (-X) is on viewer's left, left arm (+X) on viewer's right
        assertTrue(
            front.anchors.getValue(Body3DModel.SEG_RIGHT_ARM).x < front.anchors.getValue(Body3DModel.SEG_LEFT_ARM).x,
        )
        // At yaw = 180 (back view), left/right swap horizontally on screen
        val back = Body3DModel.project(mesh, yawRad = PI.toFloat(), viewportWidth = 1000f, viewportHeight = 1000f)
        assertTrue(
            back.anchors.getValue(Body3DModel.SEG_RIGHT_ARM).x > back.anchors.getValue(Body3DModel.SEG_LEFT_ARM).x,
        )
    }

    @Test
    fun femaleProportionsHaveDistinctWaistToHipContourAndExtremeValuesStayBounded() {
        val avgMale = Body3DModel.MorphInput(
            muscleByPos = mapOf(2 to 4.1, 3 to 4.1, 12 to 33.5, 10 to 11.8, 11 to 11.8),
            fatByPos = mapOf(2 to 0.95, 3 to 0.95, 12 to 10.5, 10 to 2.8, 11 to 2.8),
            totalWeightKg = 82.0,
            gender = 0,
        )
        val avgFemale = Body3DModel.MorphInput(
            muscleByPos = mapOf(2 to 2.35, 3 to 2.35, 12 to 23.0, 10 to 8.2, 11 to 8.2),
            fatByPos = mapOf(2 to 1.10, 3 to 1.10, 12 to 10.5, 10 to 3.8, 11 to 3.8),
            totalWeightKg = 64.0,
            gender = 1,
        )
        val maleMesh = Body3DModel.buildMesh(avgMale)
        val femaleMesh = Body3DModel.buildMesh(avgFemale)
        assertTrue(femaleMesh.factors.isFemale)
        assertTrue(!maleMesh.factors.isFemale)

        fun trunkWidthAt(mesh: Body3DModel.Mesh, yCenter: Float): Float {
            val trunkVertIds = HashSet<Int>()
            for (t in mesh.triangles) {
                if (t.segmentId == Body3DModel.SEG_TRUNK) {
                    trunkVertIds.add(t.i0)
                    trunkVertIds.add(t.i1)
                    trunkVertIds.add(t.i2)
                }
            }
            val xs = trunkVertIds.map { mesh.vertices[it] }.filter { abs(it.y - yCenter) < 0.03f }.map { it.x }
            return xs.max() - xs.min()
        }
        val maleShoulderW = trunkWidthAt(maleMesh, 0.43f)
        val femaleShoulderW = trunkWidthAt(femaleMesh, 0.43f)
        val femaleWaistW = trunkWidthAt(femaleMesh, 0.21f)
        val femaleHipW = trunkWidthAt(femaleMesh, 0.00f)
        val maleWaistW = trunkWidthAt(maleMesh, 0.21f)
        val maleHipW = trunkWidthAt(maleMesh, 0.00f)

        assertTrue(maleShoulderW > femaleShoulderW * 1.20f, "Male shoulders ($maleShoulderW) must be noticeably broader than female ($femaleShoulderW)")
        assertTrue((femaleWaistW / femaleHipW) < (maleWaistW / maleHipW) - 0.18f, "Female must have a distinctly stronger waist-to-hip hourglass ratio")

        val extreme = Body3DModel.MorphInput(
            muscleByPos = mapOf(2 to 50.0, 3 to 0.01, 12 to 200.0, 10 to 0.0, 11 to 80.0),
            fatByPos = mapOf(2 to 0.0, 3 to 40.0, 12 to 150.0, 10 to 40.0, 11 to 0.0),
            totalWeightKg = 250.0,
            visceralFatIdx = 20.0,
            gender = 0,
        )
        val extremeMesh = Body3DModel.buildMesh(extreme)
        assertTrue(extremeMesh.vertices.all { abs(it.x) < 2.0f && abs(it.y) <= 1.1f && abs(it.z) < 2.0f })
    }

    @Test
    fun sexSpecificFatDistributionAndroidBellyVsGynoidHipsAndWholeBodyFactory() {
        fun maxFrontZInBand(mesh: Body3DModel.Mesh, segId: Int, yLo: Float, yHi: Float): Float {
            val ids = HashSet<Int>()
            for (t in mesh.triangles) if (t.segmentId == segId) { ids += t.i0; ids += t.i1; ids += t.i2 }
            return ids.map { mesh.vertices[it] }.filter { it.y in yLo..yHi }.maxOf { it.z }
        }
        fun maxWidthInBand(mesh: Body3DModel.Mesh, segId: Int, yLo: Float, yHi: Float): Float {
            val ids = HashSet<Int>()
            for (t in mesh.triangles) if (t.segmentId == segId) { ids += t.i0; ids += t.i1; ids += t.i2 }
            val xs = ids.map { mesh.vertices[it] }.filter { it.y in yLo..yHi }.map { it.x }
            return xs.max() - xs.min()
        }

        val leanMale = Body3DModel.buildMesh(Body3DModel.fromWholeBody(weightKg = 76.0, bodyFatPct = 13.0, visceralFatIdx = 1.8, gender = 0))
        val highFatMale = Body3DModel.buildMesh(Body3DModel.fromWholeBody(weightKg = 104.0, bodyFatPct = 32.0, visceralFatIdx = 8.5, gender = 0))

        val leanFemale = Body3DModel.buildMesh(Body3DModel.fromWholeBody(weightKg = 58.0, bodyFatPct = 21.0, visceralFatIdx = 1.6, gender = 1))
        val highFatFemale = Body3DModel.buildMesh(Body3DModel.fromWholeBody(weightKg = 84.0, bodyFatPct = 39.0, visceralFatIdx = 4.2, gender = 1))

        val maleMidBellyGain = maxFrontZInBand(highFatMale, Body3DModel.SEG_TRUNK, 0.12f, 0.22f) -
            maxFrontZInBand(leanMale, Body3DModel.SEG_TRUNK, 0.12f, 0.22f)
        val femaleMidBellyGain = maxFrontZInBand(highFatFemale, Body3DModel.SEG_TRUNK, 0.12f, 0.22f) -
            maxFrontZInBand(leanFemale, Body3DModel.SEG_TRUNK, 0.12f, 0.22f)

        val femaleHipGain = maxWidthInBand(highFatFemale, Body3DModel.SEG_TRUNK, -0.04f, 0.03f) -
            maxWidthInBand(leanFemale, Body3DModel.SEG_TRUNK, -0.04f, 0.03f)
        val maleHipGain = maxWidthInBand(highFatMale, Body3DModel.SEG_TRUNK, -0.04f, 0.03f) -
            maxWidthInBand(leanMale, Body3DModel.SEG_TRUNK, -0.04f, 0.03f)

        assertTrue(maleMidBellyGain > femaleMidBellyGain * 1.25f, "Male android fat should protrude mid-belly ($maleMidBellyGain) more than female ($femaleMidBellyGain)")
        assertTrue(femaleHipGain > maleHipGain * 1.25f, "Female gynoid fat should widen hips ($femaleHipGain) more than male ($maleHipGain)")

        // ProjectionScratch produces identical triangle count and anchors
        val scratch = Body3DModel.ProjectionScratch()
        val s1 = Body3DModel.project(highFatFemale, yawRad = 0.5f, viewportWidth = 400f, viewportHeight = 400f)
        val s2 = Body3DModel.project(highFatFemale, yawRad = 0.5f, viewportWidth = 400f, viewportHeight = 400f, scratch = scratch)
        assertEquals(s1.triangles.size, s2.triangles.size)
        assertEquals(s1.anchors.keys, s2.anchors.keys)
    }
}
