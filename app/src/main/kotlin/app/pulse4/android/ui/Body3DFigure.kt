package app.pulse4.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse4.android.ui.theme.GlacierSecondaryDark
import app.pulse4.android.ui.theme.JetBrainsMonoFamily
import app.pulse4.android.ui.theme.MineralBackgroundDark
import app.pulse4.android.ui.theme.MineralOnSurfaceDark
import app.pulse4.android.ui.theme.MineralOnSurfaceVariantDark
import app.pulse4.android.ui.theme.MineralOutlineDark
import app.pulse4.android.ui.theme.MineralSurfaceDark
import app.pulse4.android.ui.theme.MineralSurfaceVariantDark
import app.pulse4.android.ui.theme.SagePrimaryDark
import app.pulse4.android.ui.theme.SandTertiaryDark
import app.pulse4.ui.Body3DModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

enum class Body3DViewMode {
    MUSCLE,
    FAT,
    COMPOSITE,
}

/** Daily whole-body composition snapshot for standard smart scales (without 5-segment Body Scan). */
data class WholeBody3DSnapshot(
    val dateLabel: String,
    val weightKg: Double,
    val fatPct: Double? = null,
    val muscleKg: Double? = null,
    val waterKg: Double? = null,
    val boneKg: Double? = null,
    val visceralFat: Double? = null,
)

private val SEAM_STROKE = Stroke(width = 0.9f)
private val PEDESTAL_STROKE = Stroke(width = 1.2f)

private data class CalloutBadgeLayout(
    val segmentId: Int,
    val anchorX: Float,
    val anchorY: Float,
    val isLeft: Boolean,
    val pillCenterY: Float,
)

private fun shortSegmentName(pos: Int): String = when (pos) {
    Body3DModel.SEG_RIGHT_ARM -> "P. ruka"
    Body3DModel.SEG_LEFT_ARM -> "L. ruka"
    Body3DModel.SEG_TRUNK -> "Trup"
    Body3DModel.SEG_RIGHT_LEG -> "P. noha"
    Body3DModel.SEG_LEFT_LEG -> "L. noha"
    else -> "Část"
}

private fun fullSegmentName(pos: Int): String = when (pos) {
    Body3DModel.SEG_RIGHT_ARM -> "Pravá ruka"
    Body3DModel.SEG_LEFT_ARM -> "Levá ruka"
    Body3DModel.SEG_TRUNK -> "Trup (hrudník a břicho)"
    Body3DModel.SEG_RIGHT_LEG -> "Pravá noha"
    Body3DModel.SEG_LEFT_LEG -> "Levá noha"
    else -> "Segment"
}

private fun segmentAccentColor(pos: Int): Color =
    BODY_SEGMENT_DEFS.firstOrNull { it.first == pos }?.third ?: Color(0xFF38BDF8)

/**
 * Exact chart-palette mapping for each 3D segment (100% consistent with [MetricPalette] and charts):
 * - MUSCLE uses strictly [MetricPalette.Muscle] (`#3B82F6`), modulated by brightness for muscle share
 * - FAT uses strictly [MetricPalette.Fat] (`#F97316`), modulated toward [MetricPalette.VisceralFat] when elevated
 * - COMPOSITE blends [MetricPalette.Muscle] (`#3B82F6`) and [MetricPalette.Fat] (`#F97316`) directly
 */
private fun segmentAccentForMode(
    segId: Int,
    mode: Body3DViewMode,
    factors: Body3DModel.MorphFactors,
): Color {
    if (segId == Body3DModel.SEG_NEUTRAL) return Color(0xFF64748B)
    val m = factors.segments[segId] ?: return MetricPalette.Muscle
    return when (mode) {
        Body3DViewMode.MUSCLE -> {
            // Pure MetricPalette.Muscle (#3B82F6): brighter/richer for higher muscle share, deeper slate-blue for lower
            val t = ((m.musclePct - 60.0) / 25.0).toFloat().coerceIn(0f, 1f)
            lerp(Color(0xFF1D4ED8), MetricPalette.Muscle, t)
        }
        Body3DViewMode.FAT -> {
            // Pure MetricPalette.Fat (#F97316): softer amber-orange for lean segments, rich orange/red for higher fat
            val t = ((m.fatPct - 10.0) / 22.0).toFloat().coerceIn(0f, 1f)
            if (t <= 0.65f) {
                lerp(Color(0xFFFDBA74), MetricPalette.Fat, t / 0.65f)
            } else {
                lerp(MetricPalette.Fat, MetricPalette.VisceralFat, ((t - 0.65f) / 0.35f).coerceIn(0f, 1f))
            }
        }
        Body3DViewMode.COMPOSITE -> {
            val fatT = ((m.fatPct - 10.0) / 22.0).toFloat().coerceIn(0f, 1f)
            lerp(MetricPalette.Muscle, MetricPalette.Fat, fatT)
        }
    }
}

private fun segmentRimColor(
    segId: Int,
    mode: Body3DViewMode,
    factors: Body3DModel.MorphFactors,
    isSelected: Boolean,
): Color {
    if (isSelected) return segmentAccentColor(segId)
    if (segId == Body3DModel.SEG_NEUTRAL) return Color(0xFF94A3B8)
    val accent = segmentAccentForMode(segId, mode, factors)
    return when (mode) {
        Body3DViewMode.MUSCLE -> lerp(accent, Color(0xFF93C5FD), 0.35f)
        Body3DViewMode.FAT -> lerp(accent, Color(0xFFFED7AA), 0.35f)
        Body3DViewMode.COMPOSITE -> lerp(accent, Color(0xFFDBEAFE), 0.28f)
    }
}

/**
 * Interactive 3D parametric human figure morphed directly from Withings Body Scan segmental data.
 *
 * Features:
 * - Real 3D triangulated anatomy shaped by per-segment muscle, fat, visceral fat, weight and gender
 * - 360° horizontal drag rotation + camera presets (front / side / back / auto-orbit)
 * - Direct tap hit-testing on 3D limbs/trunk synced with floating anti-overlap callout badges
 * - Three visual modes: Svaly, Tuk (with visceral fat core glow), and Svaly + Tuk (3D composite)
 * - Historical evolution time-lapse scrubber & playback when multiple scans exist
 */
@Composable
fun InteractiveBody3DFigure(
    snapshots: List<SegmentalBodySnapshot>,
    activeIndex: Int,
    onActiveIndexChange: (Int) -> Unit,
    viewMode: Body3DViewMode,
    onViewModeChange: (Body3DViewMode) -> Unit,
    unitMode: BodyUnitMode,
    selectedSegmentPos: Int?,
    onSelectSegment: (Int?) -> Unit,
    gender: Int = 0,
    onGenderChange: ((Int) -> Unit)? = null,
    weightByDate: Map<String, Double> = emptyMap(),
    visceralByDate: Map<String, Double> = emptyMap(),
    fallbackWeightKg: Double? = null,
    fallbackVisceralFat: Double? = null,
) {
    if (snapshots.isEmpty()) return

    val safeIdx = activeIndex.coerceIn(0, snapshots.lastIndex)
    val snap = snapshots[safeIdx]
    val prevSnap = if (safeIdx > 0) snapshots[safeIdx - 1] else null
    val firstSnap = snapshots.first()

    val snapWeight = weightByDate[snap.dateLabel] ?: fallbackWeightKg
    val snapVisceral = visceralByDate[snap.dateLabel] ?: fallbackVisceralFat

    val morphInput = remember(snap, snapWeight, snapVisceral, gender) {
        Body3DModel.MorphInput(
            muscleByPos = snap.muscleByPos,
            fatByPos = snap.fatByPos,
            ffmByPos = snap.ffmByPos,
            totalWeightKg = snapWeight,
            visceralFatIdx = snapVisceral,
            gender = gender,
        )
    }
    val mesh = remember(morphInput) { Body3DModel.buildMesh(morphInput) }

    // Camera yaw (radians) & gentle auto-orbit / preset target
    var yawRad by remember { mutableFloatStateOf(0.18f) } // slight 10° 3/4 hero angle on first render
    var autoRotate by remember { mutableStateOf(false) }
    var targetYawRad by remember { mutableStateOf<Float?>(null) }
    // Keep pulsePhase as State object so only Canvas draw scope observes it (zero Composable recompositions!)
    val pulsePhaseState = remember { mutableFloatStateOf(0f) }
    val showVisceralLoop = (viewMode == Body3DViewMode.FAT || viewMode == Body3DViewMode.COMPOSITE) && snapVisceral != null

    // Historical time-lapse playback
    var playingTimeline by remember { mutableStateOf(false) }

    LaunchedEffect(autoRotate, targetYawRad, showVisceralLoop) {
        if (!autoRotate && targetYawRad == null && !showVisceralLoop) return@LaunchedEffect
        var lastNs = 0L
        var lastPulseNs = 0L
        while (autoRotate || targetYawRad != null || showVisceralLoop) {
            withFrameNanos { nowNs ->
                if (lastNs != 0L) {
                    val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    if (autoRotate || targetYawRad != null) {
                        if (showVisceralLoop) {
                            pulsePhaseState.floatValue = (pulsePhaseState.floatValue + dt * 2.2f) % (2f * PI.toFloat())
                            lastPulseNs = nowNs
                        }
                        if (autoRotate) {
                            yawRad = normalizeAngle(yawRad + dt * 0.72f)
                        } else if (targetYawRad != null) {
                            val target = targetYawRad!!
                            val diff = shortestAngleDiff(yawRad, target)
                            if (abs(diff) < 0.01f) {
                                yawRad = normalizeAngle(target)
                                targetYawRad = null
                            } else {
                                yawRad = normalizeAngle(yawRad + diff * (dt * 9.5f).coerceAtMost(0.45f))
                            }
                        }
                    } else if (showVisceralLoop && (nowNs - lastPulseNs) >= 33_000_000L) {
                        // Throttle idle breathing glow to ~30fps instead of 120Hz GPU redraws
                        val pdt = if (lastPulseNs == 0L) 0.033f else ((nowNs - lastPulseNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                        pulsePhaseState.floatValue = (pulsePhaseState.floatValue + pdt * 2.2f) % (2f * PI.toFloat())
                        lastPulseNs = nowNs
                    }
                }
                lastNs = nowNs
            }
        }
    }

    LaunchedEffect(playingTimeline, snapshots.size) {
        if (!playingTimeline || snapshots.size < 2) return@LaunchedEffect
        var idx = if (safeIdx >= snapshots.lastIndex) 0 else safeIdx
        while (playingTimeline) {
            onActiveIndexChange(idx)
            delay(650L)
            idx++
            if (idx > snapshots.lastIndex) {
                playingTimeline = false
                break
            }
        }
    }

    val accentGlow = when (viewMode) {
        Body3DViewMode.MUSCLE -> MetricPalette.Muscle
        Body3DViewMode.FAT -> MetricPalette.Fat
        Body3DViewMode.COMPOSITE -> GlacierSecondaryDark
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 3D mode switcher: Svaly · Tuk · 3D Mix
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = viewMode == Body3DViewMode.MUSCLE,
                onClick = { onViewModeChange(Body3DViewMode.MUSCLE) },
                leadingIcon = { Icon(PulseIcons.Muscle, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("Svaly", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == Body3DViewMode.FAT,
                onClick = { onViewModeChange(Body3DViewMode.FAT) },
                leadingIcon = { Icon(PulseIcons.BodyFat, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("Tuk", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == Body3DViewMode.COMPOSITE,
                onClick = { onViewModeChange(Body3DViewMode.COMPOSITE) },
                leadingIcon = { Icon(Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("3D Mix", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
        }

        // Main interactive 3D studio stage — Nordic obsidian mineral backdrop matching Oura / Pulse4 cards
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(352.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentGlow.copy(alpha = 0.11f),
                            MineralSurfaceDark,
                            MineralBackgroundDark,
                        ),
                    ),
                )
                .border(1.dp, MineralOutlineDark, RoundedCornerShape(18.dp)),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val pillWidthDp = if (maxWidth < 360.dp) 96.dp else 108.dp
            val pillWidthPx = with(density) { pillWidthDp.toPx() }
            val pillHeightPx = with(density) { 46.dp.toPx() }
            val sidePadPx = with(density) { 8.dp.toPx() }

            val pitchRad = -0.07f
            val projectionScratch = remember { Body3DModel.ProjectionScratch() }
            val scene = remember(mesh, yawRad, widthPx, heightPx) {
                Body3DModel.project(
                    mesh = mesh,
                    yawRad = yawRad,
                    pitchRad = pitchRad,
                    viewportWidth = widthPx,
                    viewportHeight = heightPx,
                    scratch = projectionScratch,
                )
            }

            // Compute non-overlapping left/right callout layouts from live 3D anchors
            val callouts = remember(scene, widthPx, heightPx, pillHeightPx) {
                layoutCallouts(
                    scene = scene,
                    viewportWidth = widthPx,
                    viewportHeight = heightPx,
                    pillHeightPx = pillHeightPx,
                )
            }

            // Precompute per-segment accent & rim lookup tables once per mode/selection change (not 1,200x per frame!)
            val accentLut = remember(viewMode, mesh.factors) {
                Array(16) { segId -> segmentAccentForMode(segId, viewMode, mesh.factors) }
            }
            val rimLut = remember(viewMode, mesh.factors, selectedSegmentPos) {
                Array(16) { segId -> segmentRimColor(segId, viewMode, mesh.factors, selectedSegmentPos == segId) }
            }

            val reusablePath = remember { Path() }
            val reusablePedestalPath = remember { Path() }

            // 3D Canvas: holographic pedestal + volumetric body mesh + visceral core + leader lines
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                autoRotate = false
                                targetYawRad = null
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                yawRad = normalizeAngle(yawRad + dragAmount * 0.011f)
                            },
                        )
                    }
                    .pointerInput(scene, selectedSegmentPos) {
                        detectTapGestures { tapOffset ->
                            val hit = Body3DModel.hitTestSegment(scene, tapOffset.x, tapOffset.y)
                            if (hit != null) {
                                onSelectSegment(if (selectedSegmentPos == hit) null else hit)
                            } else if (selectedSegmentPos != null) {
                                onSelectSegment(null)
                            }
                        }
                    },
            ) {
                // 1. Subtle holographic studio backdrop glow + floor pedestal rings
                drawHolographicPedestal(
                    yawRad = yawRad,
                    pitchRad = pitchRad,
                    width = size.width,
                    height = size.height,
                    accent = accentGlow,
                    ringPath = reusablePedestalPath,
                )

                val visceralVal = snapVisceral
                val showVisceral = (viewMode == Body3DViewMode.FAT || viewMode == Body3DViewMode.COMPOSITE) &&
                    visceralVal != null
                val currentPulse = if (showVisceral) pulsePhaseState.floatValue else 0f
                var visceralDrawn = false

                // 2. Render all 3D triangles in painter's back-to-front order (Nordic obsidian rim-lit sculpture)
                for (t in scene.triangles) {
                    if (showVisceral && !visceralDrawn && t.depth >= scene.visceralDepth) {
                        drawVisceralCore(
                            cx = scene.visceralCenterX,
                            cy = scene.visceralCenterY,
                            radius = scene.visceralScreenRadius,
                            visceralFat = visceralVal,
                            pulsePhase = currentPulse,
                        )
                        visceralDrawn = true
                    }

                    val segIdx = t.segmentId.coerceIn(0, 15)
                    val isSelected = selectedSegmentPos == t.segmentId
                    val isDimmed = selectedSegmentPos != null && !isSelected && t.segmentId != Body3DModel.SEG_NEUTRAL
                    val accent = accentLut[segIdx]
                    val rim = rimLut[segIdx]

                    val shaded = shadeObsidianSculpture(
                        accent = accent,
                        rim = rim,
                        diffuse = t.diffuse,
                        fill = t.fill,
                        fresnel = t.fresnel,
                        specular = t.specular,
                        isSelected = isSelected,
                        isDimmed = isDimmed,
                        isNeutral = t.segmentId == Body3DModel.SEG_NEUTRAL,
                        translucentTrunk = showVisceral && t.segmentId == Body3DModel.SEG_TRUNK && t.depth > scene.visceralDepth,
                    )

                    reusablePath.reset()
                    reusablePath.moveTo(t.x0, t.y0)
                    reusablePath.lineTo(t.x1, t.y1)
                    reusablePath.lineTo(t.x2, t.y2)
                    reusablePath.close()
                    drawPath(path = reusablePath, color = shaded)
                    // Hairline same-color stroke seals sub-pixel anti-aliasing seams between adjacent triangles
                    drawPath(path = reusablePath, color = shaded, style = SEAM_STROKE)

                    // Whisper-subtle anatomical contour line (accentuates 3D curvature without wireframe clutter)
                    if (t.contourEdge && t.fresnel < 0.80f) {
                        val lineAlpha = when {
                            isSelected -> 0.40f
                            isDimmed -> 0.04f
                            t.segmentId == Body3DModel.SEG_NEUTRAL -> 0.08f
                            else -> 0.14f
                        }
                        drawLine(
                            color = rim.copy(alpha = lineAlpha),
                            start = Offset(t.x0, t.y0),
                            end = Offset(t.x1, t.y1),
                            strokeWidth = if (isSelected) 1.5f else 0.9f,
                        )
                    }
                }
                if (showVisceral && !visceralDrawn) {
                    drawVisceralCore(
                        cx = scene.visceralCenterX,
                        cy = scene.visceralCenterY,
                        radius = scene.visceralScreenRadius,
                        visceralFat = visceralVal,
                        pulsePhase = currentPulse,
                    )
                }

                // 3. Draw 3D-tracked leader lines + anchor points for each measured segment
                for (c in callouts) {
                    val isSelected = selectedSegmentPos == c.segmentId
                    val isDimmed = selectedSegmentPos != null && !isSelected
                    val segColor = if (isSelected) Color(0xFFE0F2FE) else segmentAccentForMode(c.segmentId, viewMode, mesh.factors)
                    val lineAlpha = when {
                        isSelected -> 0.90f
                        isDimmed -> 0.18f
                        else -> 0.45f
                    }
                    val pillEdgeX = if (c.isLeft) sidePadPx + pillWidthPx else size.width - sidePadPx - pillWidthPx
                    val elbowX = if (c.isLeft) pillEdgeX + 12f else pillEdgeX - 12f

                    drawLine(
                        color = segColor.copy(alpha = lineAlpha),
                        start = Offset(c.anchorX, c.anchorY),
                        end = Offset(elbowX, c.pillCenterY),
                        strokeWidth = if (isSelected) 2.0f else 1.15f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = segColor.copy(alpha = lineAlpha),
                        start = Offset(elbowX, c.pillCenterY),
                        end = Offset(pillEdgeX, c.pillCenterY),
                        strokeWidth = if (isSelected) 2.0f else 1.15f,
                        cap = StrokeCap.Round,
                    )
                    // Subtle anchor dot on the 3D surface
                    drawCircle(
                        color = segColor.copy(alpha = if (isSelected) 0.40f else 0.22f),
                        radius = if (isSelected) 8.5f else 5.5f,
                        center = Offset(c.anchorX, c.anchorY),
                    )
                    drawCircle(
                        color = if (isSelected) Color.White else segColor,
                        radius = if (isSelected) 3.6f else 2.6f,
                        center = Offset(c.anchorX, c.anchorY),
                    )
                }
            }

            // Minimal Nordic top HUD: angle + Muž/Žena toggle + visceral fat chip
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val deg = (((yawRad * 180f / PI.toFloat()) % 360f) + 360f).roundToInt() % 360
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MineralSurfaceDark.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, MineralOutlineDark),
                    ) {
                        Text(
                            text = "3D · ${deg}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = MineralOnSurfaceVariantDark,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    if (onGenderChange != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MineralSurfaceDark.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MineralOutlineDark),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val maleSel = gender != 1
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp))
                                        .background(if (maleSel) accentGlow.copy(alpha = 0.24f) else Color.Transparent)
                                        .clickable { onGenderChange(0) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = "♂ Muž",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (maleSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (maleSel) MineralOnSurfaceDark else MineralOnSurfaceVariantDark,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp))
                                        .background(if (!maleSel) accentGlow.copy(alpha = 0.24f) else Color.Transparent)
                                        .clickable { onGenderChange(1) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = "♀ Žena",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (!maleSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!maleSel) MineralOnSurfaceDark else MineralOnSurfaceVariantDark,
                                    )
                                }
                            }
                        }
                    }
                }
                if (snapVisceral != null) {
                    val vfColor = when {
                        snapVisceral < 3.0 -> SagePrimaryDark
                        snapVisceral < 4.5 -> SandTertiaryDark
                        else -> MetricPalette.VisceralFat
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MineralSurfaceDark.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, vfColor.copy(alpha = 0.45f)),
                    ) {
                        Text(
                            text = "Visc. ${fmt.number(snapVisceral, 1)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = vfColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // Floating dark-mineral callout pills positioned around the 3D sculpture
            for (c in callouts) {
                val seg = mesh.factors.segments[c.segmentId] ?: continue
                val isSelected = selectedSegmentPos == c.segmentId
                val isDimmed = selectedSegmentPos != null && !isSelected
                val metricColor = segmentAccentForMode(c.segmentId, viewMode, mesh.factors)
                val borderColor = if (isSelected) GlacierSecondaryDark else MineralOutlineDark

                val primaryText = when (viewMode) {
                    Body3DViewMode.MUSCLE -> if (unitMode == BodyUnitMode.KG) fmt.weight(seg.muscleKg) else "${fmt.number(seg.musclePct, 1)} %"
                    Body3DViewMode.FAT -> if (unitMode == BodyUnitMode.KG) fmt.weight(seg.fatKg) else "${fmt.number(seg.fatPct, 1)} %"
                    Body3DViewMode.COMPOSITE -> if (unitMode == BodyUnitMode.KG) {
                        "${fmt.number(fmt.convertWeight(seg.muscleKg), 1)} / ${fmt.number(fmt.convertWeight(seg.fatKg), 1)}"
                    } else {
                        "${fmt.number(seg.musclePct, 0)}% / ${fmt.number(seg.fatPct, 0)}%"
                    }
                }
                val secondaryText = when (viewMode) {
                    Body3DViewMode.MUSCLE -> if (unitMode == BodyUnitMode.KG) {
                        "${fmt.number(seg.musclePct, 0)}% · tuk ${fmt.number(fmt.convertWeight(seg.fatKg), 1)}"
                    } else {
                        "${fmt.weight(seg.muscleKg)} · tuk ${fmt.number(seg.fatPct, 0)}%"
                    }
                    Body3DViewMode.FAT -> if (unitMode == BodyUnitMode.KG) {
                        "${fmt.number(seg.fatPct, 1)}% · sv. ${fmt.number(fmt.convertWeight(seg.muscleKg), 1)}"
                    } else {
                        "${fmt.weight(seg.fatKg)} · sv. ${fmt.number(seg.musclePct, 0)}%"
                    }
                    Body3DViewMode.COMPOSITE -> if (unitMode == BodyUnitMode.KG) {
                        "svaly / tuk (${fmt.weightUnit()})"
                    } else {
                        "svaly / tuk"
                    }
                }

                val pillX = if (c.isLeft) sidePadPx else widthPx - sidePadPx - pillWidthPx
                val pillY = (c.pillCenterY - pillHeightPx * 0.5f).coerceIn(34f, heightPx - pillHeightPx - 8f)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MineralSurfaceVariantDark.copy(alpha = 0.94f) else MineralSurfaceDark.copy(alpha = 0.84f),
                    border = BorderStroke(if (isSelected) 1.3.dp else 1.dp, borderColor),
                    modifier = Modifier
                        .offset { IntOffset(pillX.roundToInt(), pillY.roundToInt()) }
                        .width(pillWidthDp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectSegment(if (isSelected) null else c.segmentId) },
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(5.5.dp)
                                    .clip(CircleShape)
                                    .background(segmentAccentColor(c.segmentId)),
                            )
                            Text(
                                text = shortSegmentName(c.segmentId),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDimmed) Color(0xFF64748B) else MineralOnSurfaceVariantDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = primaryText,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDimmed) MineralOnSurfaceDark.copy(alpha = 0.45f) else metricColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = secondaryText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MineralOnSurfaceVariantDark.copy(alpha = if (isDimmed) 0.45f else 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Camera angle preset buttons + 360° auto-orbit toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraPresetChip("Zepředu", active = !autoRotate && abs(shortestAngleDiff(yawRad, 0f)) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = 0f
            }
            CameraPresetChip("Z boku", active = !autoRotate && abs(shortestAngleDiff(yawRad, (PI / 2.2).toFloat())) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = (PI / 2.2).toFloat()
            }
            CameraPresetChip("Zezadu", active = !autoRotate && abs(shortestAngleDiff(yawRad, PI.toFloat())) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = PI.toFloat()
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (autoRotate) accentGlow.copy(alpha = 0.18f) else MineralSurfaceDark,
                border = BorderStroke(1.dp, if (autoRotate) accentGlow else MineralOutlineDark),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        targetYawRad = null
                        autoRotate = !autoRotate
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (autoRotate) Icons.Outlined.Pause else Icons.Outlined.Autorenew,
                        contentDescription = "Otáčet 360°",
                        tint = if (autoRotate) accentGlow else MineralOnSurfaceVariantDark,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "360°",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (autoRotate) accentGlow else MineralOnSurfaceVariantDark,
                    )
                }
            }
        }

        // Focused detail panel when user taps any limb or trunk on the 3D model
        val selSeg = selectedSegmentPos?.let { mesh.factors.segments[it] }
        AnimatedVisibility(visible = selSeg != null) {
            if (selSeg != null) {
                val prevM = prevSnap?.muscleByPos?.get(selSeg.pos)
                val prevF = prevSnap?.fatByPos?.get(selSeg.pos)
                val diffM = prevM?.let { selSeg.muscleKg - it }
                val diffF = prevF?.let { selSeg.fatKg - it }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MineralSurfaceVariantDark,
                    border = BorderStroke(1.dp, segmentAccentColor(selSeg.pos).copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(segmentAccentColor(selSeg.pos)))
                                Text(
                                    fullSegmentName(selSeg.pos),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MineralOnSurfaceDark,
                                )
                            }
                            Text(
                                "Klepni znovu pro zrušení",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MineralOnSurfaceVariantDark,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SegmentDetailMetric(
                                label = "Svaly",
                                value = "${fmt.weight(selSeg.muscleKg)} (${fmt.number(selSeg.musclePct, 1)}%)",
                                delta = diffM?.let { d -> "${if (d >= 0) "+" else ""}${fmt.number(fmt.convertWeight(d), 2)} ${fmt.weightUnit()}" },
                                valueColor = MetricPalette.Muscle,
                            )
                            SegmentDetailMetric(
                                label = "Tuk",
                                value = "${fmt.weight(selSeg.fatKg)} (${fmt.number(selSeg.fatPct, 1)}%)",
                                delta = diffF?.let { d -> "${if (d >= 0) "+" else ""}${fmt.number(fmt.convertWeight(d), 2)} ${fmt.weightUnit()}" },
                                valueColor = MetricPalette.Fat,
                            )
                            SegmentDetailMetric(
                                label = "Beztuková",
                                value = fmt.weight(selSeg.ffmKg),
                                delta = "${fmt.number(selSeg.shareOfTotalMusclePct, 1)}% svalů",
                                valueColor = MetricPalette.FatFreeMass,
                            )
                        }
                    }
                }
            }
        }

        // Historical time-lapse morphing scrubber ("Proměna postavy v čase")
        if (snapshots.size >= 2) {
            val totalM = snap.muscleByPos.values.sum()
            val firstM = firstSnap.muscleByPos.values.sum()
            val totalF = snap.fatByPos.values.sum()
            val firstF = firstSnap.fatByPos.values.sum()
            val dMuscle = totalM - firstM
            val dFat = totalF - firstF

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MineralSurfaceDark,
                border = BorderStroke(1.dp, MineralOutlineDark),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = accentGlow.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, accentGlow.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable { playingTimeline = !playingTimeline },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playingTimeline) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = "Přehrát proměnu",
                                        tint = accentGlow,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    "Proměna postavy · ${snap.dateLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MineralOnSurfaceDark,
                                )
                                Text(
                                    "Od ${firstSnap.dateLabel}: svaly ${signedKg(dMuscle)} · tuk ${signedKg(dFat)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    color = MineralOnSurfaceVariantDark,
                                )
                            }
                        }
                        Text(
                            "${safeIdx + 1}/${snapshots.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MineralOnSurfaceVariantDark,
                        )
                    }
                    Slider(
                        value = safeIdx.toFloat(),
                        onValueChange = {
                            playingTimeline = false
                            onActiveIndexChange(it.roundToInt().coerceIn(0, snapshots.lastIndex))
                        },
                        valueRange = 0f..snapshots.lastIndex.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = accentGlow,
                            activeTrackColor = accentGlow,
                            inactiveTrackColor = MineralOutlineDark,
                        ),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                }
            }
        }
    }
}

private fun signedKg(deltaKg: Double): String {
    val s = if (deltaKg >= 0.0) "+" else ""
    return "$s${fmt.number(fmt.convertWeight(deltaKg), 1)} ${fmt.weightUnit()}"
}

@Composable
private fun CameraPresetChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) MineralSurfaceVariantDark else MineralSurfaceDark,
        border = BorderStroke(1.dp, if (active) GlacierSecondaryDark.copy(alpha = 0.75f) else MineralOutlineDark),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                color = if (active) MineralOnSurfaceDark else MineralOnSurfaceVariantDark,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SegmentDetailMetric(
    label: String,
    value: String,
    delta: String?,
    valueColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MineralOnSurfaceVariantDark)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        if (delta != null) {
            Text(
                delta,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 10.sp,
                color = MineralOnSurfaceDark.copy(alpha = 0.80f),
            )
        }
    }
}

private fun normalizeAngle(rad: Float): Float {
    val twoPi = (2.0 * PI).toFloat()
    var r = rad % twoPi
    if (r < 0f) r += twoPi
    return r
}

private fun shortestAngleDiff(from: Float, to: Float): Float {
    val twoPi = (2.0 * PI).toFloat()
    var d = (to - from) % twoPi
    if (d > PI.toFloat()) d -= twoPi
    if (d < -PI.toFloat()) d += twoPi
    return d
}

/**
 * Nordic Obsidian Rim-Lit shader matching Pulse4's dark studio body photography (`img_motif_body_silhouette`):
 * deep matte obsidian/slate interior softly tinted by the segment's metric color, sculpted by crisp
 * directional highlights and luminous Fresnel edge lighting.
 */
private fun shadeObsidianSculpture(
    accent: Color,
    rim: Color,
    diffuse: Float,
    fill: Float,
    fresnel: Float,
    specular: Float,
    isSelected: Boolean,
    isDimmed: Boolean,
    isNeutral: Boolean,
    translucentTrunk: Boolean,
): Color {
    // Deep matte mineral-basalt core softly tinted by the active metric accent
    val obsidian = Color(0xFF101926)
    val tintMix = when {
        isNeutral -> 0.14f
        isSelected -> 0.44f
        isDimmed -> 0.16f
        else -> 0.30f
    }
    val base = lerp(obsidian, accent, tintMix)

    val ambient = if (isNeutral) 0.34f else 0.36f
    val light = (ambient + diffuse * 0.72f + fill * 0.24f).coerceIn(0.24f, 1.20f)
    val dimMul = if (isDimmed) 0.62f else if (isSelected) 1.15f else 1.0f

    var r = base.red * light * dimMul
    var g = base.green * light * dimMul
    var b = base.blue * light * dimMul

    // Signature Oura / Nordic luminous Fresnel edge lighting
    val rimStrength = when {
        isSelected -> 0.92f
        isNeutral -> 0.28f
        isDimmed -> 0.24f
        else -> 0.68f
    }
    val rimGlow = fresnel * rimStrength
    r += rim.red * rimGlow
    g += rim.green * rimGlow
    b += rim.blue * rimGlow

    // Soft studio sheen highlight
    val specWeight = specular * (if (isSelected) 0.36f else if (isNeutral) 0.14f else 0.22f)
    r += rim.red * specWeight * 0.65f + specWeight * 0.35f
    g += rim.green * specWeight * 0.65f + specWeight * 0.35f
    b += rim.blue * specWeight * 0.65f + specWeight * 0.35f

    val alpha = when {
        translucentTrunk -> if (isSelected) 0.80f else 0.72f
        isDimmed -> 0.88f
        else -> 0.99f
    }
    return Color(
        red = r.coerceIn(0f, 1f),
        green = g.coerceIn(0f, 1f),
        blue = b.coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun DrawScope.drawHolographicPedestal(
    yawRad: Float,
    pitchRad: Float,
    width: Float,
    height: Float,
    accent: Color,
    ringPath: Path,
) {
    val floorY = -1.01f
    val center = Body3DModel.projectPoint(Body3DModel.Vec3(0f, floorY, 0f), yawRad, pitchRad, width, height)
    // Soft ambient stone-plinth reflection under feet
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.14f), accent.copy(alpha = 0.03f), Color.Transparent),
            center = Offset(center.first, center.second),
            radius = width * 0.26f,
        ),
        radius = width * 0.26f,
        center = Offset(center.first, center.second),
    )

    // Clean twin hairline rings (calm Nordic restraint instead of busy sci-fi radar)
    val steps = 36
    val radii = floatArrayOf(0.28f, 0.42f)
    val alphas = floatArrayOf(0.16f, 0.28f)
    for (rIdx in 0..1) {
        val radius = radii[rIdx]
        val alpha = alphas[rIdx]
        ringPath.reset()
        for (i in 0..steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            val p = Body3DModel.projectPoint(
                Body3DModel.Vec3(cos(theta) * radius, floorY, sin(theta) * radius),
                yawRad,
                pitchRad,
                width,
                height,
            )
            if (i == 0) ringPath.moveTo(p.first, p.second) else ringPath.lineTo(p.first, p.second)
        }
        drawPath(ringPath, color = accent.copy(alpha = alpha), style = PEDESTAL_STROKE)
    }

    // Minimal cardinal rotation markers (0° / 90° / 180° / 270°)
    for (i in 0 until 4) {
        val theta = (2.0 * PI * i / 4.0).toFloat()
        val inner = Body3DModel.projectPoint(
            Body3DModel.Vec3(cos(theta) * 0.39f, floorY, sin(theta) * 0.39f),
            yawRad,
            pitchRad,
            width,
            height,
        )
        val outer = Body3DModel.projectPoint(
            Body3DModel.Vec3(cos(theta) * 0.45f, floorY, sin(theta) * 0.45f),
            yawRad,
            pitchRad,
            width,
            height,
        )
        drawLine(
            color = accent.copy(alpha = 0.42f),
            start = Offset(inner.first, inner.second),
            end = Offset(outer.first, outer.second),
            strokeWidth = 1.6f,
        )
    }
}

private fun DrawScope.drawVisceralCore(
    cx: Float,
    cy: Float,
    radius: Float,
    visceralFat: Double,
    pulsePhase: Float,
) {
    val pulse = 1.0f + sin(pulsePhase) * 0.07f
    val r = (radius * pulse).coerceAtLeast(10f)
    val coreColor = when {
        visceralFat < 3.0 -> MetricPalette.FatFreeMass
        visceralFat < 4.5 -> MetricPalette.Fat
        else -> MetricPalette.VisceralFat
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFEF3C7).copy(alpha = 0.65f),
                coreColor.copy(alpha = 0.72f),
                coreColor.copy(alpha = 0.18f),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = r * 1.40f,
        ),
        radius = r * 1.40f,
        center = Offset(cx, cy),
    )
}

/**
 * Splits the 5 segment callouts cleanly between left and right viewport margins based on live 3D projection,
 * then relaxes vertical positions so pills never overlap even at side profiles (90° / 270°).
 */
private fun layoutCallouts(
    scene: Body3DModel.ProjectedScene,
    viewportWidth: Float,
    viewportHeight: Float,
    pillHeightPx: Float,
): List<CalloutBadgeLayout> {
    val cx = viewportWidth * 0.5f
    val leftItems = mutableListOf<Body3DModel.ProjectedAnchor>()
    val rightItems = mutableListOf<Body3DModel.ProjectedAnchor>()

    for (segId in Body3DModel.MEASURED_SEGMENTS) {
        val a = scene.anchors[segId] ?: continue
        if (segId == Body3DModel.SEG_TRUNK) {
            // Dock trunk on whichever side currently has fewer badges (prefer right when tied)
            if (leftItems.size < rightItems.size) leftItems += a else rightItems += a
        } else if (a.x < cx) {
            leftItems += a
        } else {
            rightItems += a
        }
    }
    // Balance if 4+ ended up on one side (e.g. near 90° profile)
    while (leftItems.size > 3) {
        val moved = leftItems.maxByOrNull { it.x } ?: break
        leftItems.remove(moved)
        rightItems += moved
    }
    while (rightItems.size > 3) {
        val moved = rightItems.minByOrNull { it.x } ?: break
        rightItems.remove(moved)
        leftItems += moved
    }

    fun relaxColumn(items: List<Body3DModel.ProjectedAnchor>, isLeft: Boolean): List<CalloutBadgeLayout> {
        val sorted = items.sortedBy { it.y }
        val minY = 44f + pillHeightPx * 0.5f
        val maxY = viewportHeight - 14f - pillHeightPx * 0.5f
        val minGap = pillHeightPx + 10f
        val ys = FloatArray(sorted.size) { i -> sorted[i].y.coerceIn(minY, maxY) }
        // Forward pass: push overlapping pills downward
        for (i in 1 until ys.size) {
            if (ys[i] - ys[i - 1] < minGap) {
                ys[i] = ys[i - 1] + minGap
            }
        }
        // Backward pass: clamp to bottom edge and push upward if needed
        if (ys.isNotEmpty() && ys.last() > maxY) {
            ys[ys.lastIndex] = maxY
            for (i in ys.lastIndex - 1 downTo 0) {
                if (ys[i + 1] - ys[i] < minGap) {
                    ys[i] = (ys[i + 1] - minGap).coerceAtLeast(minY)
                }
            }
        }
        return sorted.mapIndexed { idx, a ->
            CalloutBadgeLayout(
                segmentId = a.segmentId,
                anchorX = a.x,
                anchorY = a.y,
                isLeft = isLeft,
                pillCenterY = ys[idx],
            )
        }
    }

    return relaxColumn(leftItems, isLeft = true) + relaxColumn(rightItems, isLeft = false)
}

private data class WholeBodyPillData(
    val label: String,
    val primary: String,
    val secondary: String,
    val color: Color,
)

/**
 * Interactive 3D human figure driven by whole-body scale measurements (standard Withings Body+/Smart/Comp,
 * Garmin Index S2, Health Connect, or manual weigh-ins) when 5-segment Body Scan data is unavailable.
 *
 * Uses sex-specific anatomy (male vs female frame) and physiological android/gynoid fat distribution,
 * paired with floating whole-body metric badges and a historical "Proměna postavy" time-lapse scrubber.
 */
@Composable
fun InteractiveWholeBody3DFigure(
    snapshots: List<WholeBody3DSnapshot>,
    activeIndex: Int,
    onActiveIndexChange: (Int) -> Unit,
    viewMode: Body3DViewMode,
    onViewModeChange: (Body3DViewMode) -> Unit,
    unitMode: BodyUnitMode,
    gender: Int = 0,
    onGenderChange: ((Int) -> Unit)? = null,
) {
    if (snapshots.isEmpty()) return

    val safeIdx = activeIndex.coerceIn(0, snapshots.lastIndex)
    val snap = snapshots[safeIdx]
    val firstSnap = snapshots.first()

    val morphInput = remember(snap, gender) {
        Body3DModel.fromWholeBody(
            weightKg = snap.weightKg,
            bodyFatPct = snap.fatPct,
            muscleMassKg = snap.muscleKg,
            visceralFatIdx = snap.visceralFat,
            gender = gender,
        )
    }
    val mesh = remember(morphInput) { Body3DModel.buildMesh(morphInput) }

    var yawRad by remember { mutableFloatStateOf(0.18f) }
    var autoRotate by remember { mutableStateOf(false) }
    var targetYawRad by remember { mutableStateOf<Float?>(null) }
    val pulsePhaseState = remember { mutableFloatStateOf(0f) }
    val showVisceralLoop = (viewMode == Body3DViewMode.FAT || viewMode == Body3DViewMode.COMPOSITE) && snap.visceralFat != null
    var playingTimeline by remember { mutableStateOf(false) }

    LaunchedEffect(autoRotate, targetYawRad, showVisceralLoop) {
        if (!autoRotate && targetYawRad == null && !showVisceralLoop) return@LaunchedEffect
        var lastNs = 0L
        var lastPulseNs = 0L
        while (autoRotate || targetYawRad != null || showVisceralLoop) {
            withFrameNanos { nowNs ->
                if (lastNs != 0L) {
                    val dt = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    if (autoRotate || targetYawRad != null) {
                        if (showVisceralLoop) {
                            pulsePhaseState.floatValue = (pulsePhaseState.floatValue + dt * 2.2f) % (2f * PI.toFloat())
                            lastPulseNs = nowNs
                        }
                        if (autoRotate) {
                            yawRad = normalizeAngle(yawRad + dt * 0.72f)
                        } else if (targetYawRad != null) {
                            val target = targetYawRad!!
                            val diff = shortestAngleDiff(yawRad, target)
                            if (abs(diff) < 0.01f) {
                                yawRad = normalizeAngle(target)
                                targetYawRad = null
                            } else {
                                yawRad = normalizeAngle(yawRad + diff * (dt * 9.5f).coerceAtMost(0.45f))
                            }
                        }
                    } else if (showVisceralLoop && (nowNs - lastPulseNs) >= 33_000_000L) {
                        val pdt = if (lastPulseNs == 0L) 0.033f else ((nowNs - lastPulseNs) / 1_000_000_000f).coerceIn(0f, 0.1f)
                        pulsePhaseState.floatValue = (pulsePhaseState.floatValue + pdt * 2.2f) % (2f * PI.toFloat())
                        lastPulseNs = nowNs
                    }
                }
                lastNs = nowNs
            }
        }
    }

    LaunchedEffect(playingTimeline, snapshots.size) {
        if (!playingTimeline || snapshots.size < 2) return@LaunchedEffect
        var idx = if (safeIdx >= snapshots.lastIndex) 0 else safeIdx
        while (playingTimeline) {
            onActiveIndexChange(idx)
            delay(650L)
            idx++
            if (idx > snapshots.lastIndex) {
                playingTimeline = false
                break
            }
        }
    }

    val accentGlow = when (viewMode) {
        Body3DViewMode.MUSCLE -> MetricPalette.Muscle
        Body3DViewMode.FAT -> MetricPalette.Fat
        Body3DViewMode.COMPOSITE -> GlacierSecondaryDark
    }

    // Whole-body metric pills split cleanly into left and right columns
    val leftPills = remember(snap, firstSnap, unitMode) {
        buildList {
            val dW = snap.weightKg - firstSnap.weightKg
            add(
                WholeBodyPillData(
                    label = "Váha",
                    primary = fmt.weight(snap.weightKg, 1),
                    secondary = if (snapshots.size >= 2) signedKg(dW) else "celkem",
                    color = MetricPalette.Weight,
                ),
            )
            snap.muscleKg?.let { mKg ->
                val mPct = (mKg / snap.weightKg.coerceAtLeast(1.0)) * 100.0
                add(
                    WholeBodyPillData(
                        label = "Svaly",
                        primary = if (unitMode == BodyUnitMode.KG) fmt.weight(mKg, 1) else "${fmt.number(mPct, 1)} %",
                        secondary = if (unitMode == BodyUnitMode.KG) "${fmt.number(mPct, 1)} % těla" else fmt.weight(mKg, 1),
                        color = MetricPalette.Muscle,
                    ),
                )
            }
            snap.boneKg?.let { bKg ->
                val bPct = (bKg / snap.weightKg.coerceAtLeast(1.0)) * 100.0
                add(
                    WholeBodyPillData(
                        label = "Kosti",
                        primary = if (unitMode == BodyUnitMode.KG) fmt.weight(bKg, 1) else "${fmt.number(bPct, 1)} %",
                        secondary = if (unitMode == BodyUnitMode.KG) "${fmt.number(bPct, 1)} %" else fmt.weight(bKg, 1),
                        color = MetricPalette.Bone,
                    ),
                )
            }
        }
    }

    val rightPills = remember(snap, unitMode) {
        buildList {
            snap.fatPct?.let { fPct ->
                val fKg = snap.weightKg * (fPct / 100.0)
                add(
                    WholeBodyPillData(
                        label = "Tělesný tuk",
                        primary = if (unitMode == BodyUnitMode.PERCENT) "${fmt.number(fPct, 1)} %" else fmt.weight(fKg, 1),
                        secondary = if (unitMode == BodyUnitMode.PERCENT) fmt.weight(fKg, 1) else "${fmt.number(fPct, 1)} % těla",
                        color = MetricPalette.Fat,
                    ),
                )
            }
            snap.waterKg?.let { wKg ->
                val wPct = (wKg / snap.weightKg.coerceAtLeast(1.0)) * 100.0
                add(
                    WholeBodyPillData(
                        label = "Voda",
                        primary = if (unitMode == BodyUnitMode.PERCENT) "${fmt.number(wPct, 1)} %" else fmt.weight(wKg, 1),
                        secondary = if (unitMode == BodyUnitMode.PERCENT) fmt.weight(wKg, 1) else "${fmt.number(wPct, 1)} %",
                        color = MetricPalette.Water,
                    ),
                )
            }
            snap.visceralFat?.let { vf ->
                val vfColor = when {
                    vf < 3.0 -> SagePrimaryDark
                    vf < 4.5 -> SandTertiaryDark
                    else -> MetricPalette.VisceralFat
                }
                add(
                    WholeBodyPillData(
                        label = "Viscerální",
                        primary = fmt.number(vf, 1),
                        secondary = when {
                            vf < 3.0 -> "optimální"
                            vf < 4.5 -> "zvýšený"
                            else -> "vysoký"
                        },
                        color = vfColor,
                    ),
                )
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 3D mode switcher: Svaly · Tuk · 3D Mix
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = viewMode == Body3DViewMode.MUSCLE,
                onClick = { onViewModeChange(Body3DViewMode.MUSCLE) },
                leadingIcon = { Icon(PulseIcons.Muscle, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("Svaly", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == Body3DViewMode.FAT,
                onClick = { onViewModeChange(Body3DViewMode.FAT) },
                leadingIcon = { Icon(PulseIcons.BodyFat, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("Tuk", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == Body3DViewMode.COMPOSITE,
                onClick = { onViewModeChange(Body3DViewMode.COMPOSITE) },
                leadingIcon = { Icon(Icons.Outlined.Autorenew, contentDescription = null, modifier = Modifier.size(15.dp)) },
                label = { Text("3D Mix", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.weight(1f),
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(332.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentGlow.copy(alpha = 0.11f),
                            MineralSurfaceDark,
                            MineralBackgroundDark,
                        ),
                    ),
                )
                .border(1.dp, MineralOutlineDark, RoundedCornerShape(18.dp)),
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val pillWidthDp = if (maxWidth < 360.dp) 96.dp else 108.dp
            val pitchRad = -0.07f

            val projectionScratch = remember { Body3DModel.ProjectionScratch() }
            val scene = remember(mesh, yawRad, widthPx, heightPx) {
                Body3DModel.project(
                    mesh = mesh,
                    yawRad = yawRad,
                    pitchRad = pitchRad,
                    viewportWidth = widthPx,
                    viewportHeight = heightPx,
                    scratch = projectionScratch,
                )
            }
            val accentLut = remember(viewMode, mesh.factors) {
                Array(16) { segId -> segmentAccentForMode(segId, viewMode, mesh.factors) }
            }
            val rimLut = remember(viewMode, mesh.factors) {
                Array(16) { segId -> segmentRimColor(segId, viewMode, mesh.factors, isSelected = false) }
            }
            val reusablePath = remember { Path() }
            val reusablePedestalPath = remember { Path() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                autoRotate = false
                                targetYawRad = null
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                yawRad = normalizeAngle(yawRad + dragAmount * 0.011f)
                            },
                        )
                    },
            ) {
                drawHolographicPedestal(
                    yawRad = yawRad,
                    pitchRad = pitchRad,
                    width = size.width,
                    height = size.height,
                    accent = accentGlow,
                    ringPath = reusablePedestalPath,
                )
                val visceralVal = snap.visceralFat
                val showVisceral = (viewMode == Body3DViewMode.FAT || viewMode == Body3DViewMode.COMPOSITE) && visceralVal != null
                val currentPulse = if (showVisceral) pulsePhaseState.floatValue else 0f
                var visceralDrawn = false

                for (t in scene.triangles) {
                    if (showVisceral && !visceralDrawn && t.depth >= scene.visceralDepth) {
                        drawVisceralCore(
                            cx = scene.visceralCenterX,
                            cy = scene.visceralCenterY,
                            radius = scene.visceralScreenRadius,
                            visceralFat = visceralVal,
                            pulsePhase = currentPulse,
                        )
                        visceralDrawn = true
                    }
                    val segIdx = t.segmentId.coerceIn(0, 15)
                    val shaded = shadeObsidianSculpture(
                        accent = accentLut[segIdx],
                        rim = rimLut[segIdx],
                        diffuse = t.diffuse,
                        fill = t.fill,
                        fresnel = t.fresnel,
                        specular = t.specular,
                        isSelected = false,
                        isDimmed = false,
                        isNeutral = t.segmentId == Body3DModel.SEG_NEUTRAL,
                        translucentTrunk = showVisceral && t.segmentId == Body3DModel.SEG_TRUNK && t.depth > scene.visceralDepth,
                    )
                    reusablePath.reset()
                    reusablePath.moveTo(t.x0, t.y0)
                    reusablePath.lineTo(t.x1, t.y1)
                    reusablePath.lineTo(t.x2, t.y2)
                    reusablePath.close()
                    drawPath(path = reusablePath, color = shaded)
                    drawPath(path = reusablePath, color = shaded, style = SEAM_STROKE)
                    if (t.contourEdge && t.fresnel < 0.80f) {
                        drawLine(
                            color = rimLut[segIdx].copy(alpha = if (t.segmentId == Body3DModel.SEG_NEUTRAL) 0.08f else 0.14f),
                            start = Offset(t.x0, t.y0),
                            end = Offset(t.x1, t.y1),
                            strokeWidth = 0.9f,
                        )
                    }
                }
                if (showVisceral && !visceralDrawn) {
                    drawVisceralCore(
                        cx = scene.visceralCenterX,
                        cy = scene.visceralCenterY,
                        radius = scene.visceralScreenRadius,
                        visceralFat = visceralVal,
                        pulsePhase = currentPulse,
                    )
                }
            }

            // Top HUD bar: 3D angle + Muž/Žena toggle + fat distribution hint
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val deg = (((yawRad * 180f / PI.toFloat()) % 360f) + 360f).roundToInt() % 360
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MineralSurfaceDark.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, MineralOutlineDark),
                    ) {
                        Text(
                            text = "3D · ${deg}°",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            fontSize = 10.sp,
                            color = MineralOnSurfaceVariantDark,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    if (onGenderChange != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MineralSurfaceDark.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MineralOutlineDark),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val maleSel = gender != 1
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp))
                                        .background(if (maleSel) accentGlow.copy(alpha = 0.24f) else Color.Transparent)
                                        .clickable { onGenderChange(0) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = "♂ Muž",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (maleSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (maleSel) MineralOnSurfaceDark else MineralOnSurfaceVariantDark,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp))
                                        .background(if (!maleSel) accentGlow.copy(alpha = 0.24f) else Color.Transparent)
                                        .clickable { onGenderChange(1) }
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = "♀ Žena",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (!maleSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!maleSel) MineralOnSurfaceDark else MineralOnSurfaceVariantDark,
                                    )
                                }
                            }
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MineralSurfaceDark.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MineralOutlineDark),
                ) {
                    Text(
                        text = snap.dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        color = MineralOnSurfaceVariantDark,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            // Left whole-body metric column
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp, top = 30.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pill in leftPills) {
                    WholeBodyMetricPill(pill, pillWidthDp)
                }
            }

            // Right whole-body metric column
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp, top = 30.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (pill in rightPills) {
                    WholeBodyMetricPill(pill, pillWidthDp)
                }
            }
        }

        // Camera angle preset buttons + 360° auto-orbit toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraPresetChip("Zepředu", active = !autoRotate && abs(shortestAngleDiff(yawRad, 0f)) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = 0f
            }
            CameraPresetChip("Z boku", active = !autoRotate && abs(shortestAngleDiff(yawRad, (PI / 2.2).toFloat())) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = (PI / 2.2).toFloat()
            }
            CameraPresetChip("Zezadu", active = !autoRotate && abs(shortestAngleDiff(yawRad, PI.toFloat())) < 0.28f, modifier = Modifier.weight(1f)) {
                autoRotate = false
                targetYawRad = PI.toFloat()
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (autoRotate) accentGlow.copy(alpha = 0.18f) else MineralSurfaceDark,
                border = BorderStroke(1.dp, if (autoRotate) accentGlow else MineralOutlineDark),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        targetYawRad = null
                        autoRotate = !autoRotate
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (autoRotate) Icons.Outlined.Pause else Icons.Outlined.Autorenew,
                        contentDescription = "Otáčet 360°",
                        tint = if (autoRotate) accentGlow else MineralOnSurfaceVariantDark,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "360°",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (autoRotate) accentGlow else MineralOnSurfaceVariantDark,
                    )
                }
            }
        }

        // Historical time-lapse morphing scrubber ("Proměna postavy v čase")
        if (snapshots.size >= 2) {
            val dWeight = snap.weightKg - firstSnap.weightKg
            val dFatPct = if (snap.fatPct != null && firstSnap.fatPct != null) snap.fatPct - firstSnap.fatPct else null
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MineralSurfaceDark,
                border = BorderStroke(1.dp, MineralOutlineDark),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = CircleShape,
                                color = accentGlow.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, accentGlow.copy(alpha = 0.55f)),
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable { playingTimeline = !playingTimeline },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (playingTimeline) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = "Přehrát proměnu",
                                        tint = accentGlow,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    "Proměna postavy · ${snap.dateLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MineralOnSurfaceDark,
                                )
                                val fatSummary = dFatPct?.let { d ->
                                    val s = if (d >= 0.0) "+" else ""
                                    " · tuk $s${fmt.number(d, 1)} %"
                                }.orEmpty()
                                Text(
                                    "Od ${firstSnap.dateLabel}: váha ${signedKg(dWeight)}$fatSummary",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontSize = 10.sp,
                                    color = MineralOnSurfaceVariantDark,
                                )
                            }
                        }
                        Text(
                            "${safeIdx + 1}/${snapshots.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFamily,
                            color = MineralOnSurfaceVariantDark,
                        )
                    }
                    Slider(
                        value = safeIdx.toFloat(),
                        onValueChange = {
                            playingTimeline = false
                            onActiveIndexChange(it.roundToInt().coerceIn(0, snapshots.lastIndex))
                        },
                        valueRange = 0f..snapshots.lastIndex.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = accentGlow,
                            activeTrackColor = accentGlow,
                            inactiveTrackColor = MineralOutlineDark,
                        ),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WholeBodyMetricPill(
    data: WholeBodyPillData,
    widthDp: androidx.compose.ui.unit.Dp,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MineralSurfaceDark.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, MineralOutlineDark),
        modifier = Modifier.width(widthDp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(5.5.dp)
                        .clip(CircleShape)
                        .background(data.color),
                )
                Text(
                    text = data.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MineralOnSurfaceVariantDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = data.primary,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = data.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = data.secondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = MineralOnSurfaceVariantDark.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
