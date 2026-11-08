package app.pulse4.android.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pulse4.android.R
import kotlin.math.sin

enum class AtmosphereStyle {
    NORDIC_AURORA,
    ALPINE_DAWN,
    DEEP_FJORD,
    WARM_TERRACOTTA,
    HEART_PULSE,
    HRV_AUTONOMIC,
    SLEEP_NOCTURNAL,
    BODY_SILHOUETTE,
    RUNNING_STRIDE
}

enum class MetricMotifKind {
    HEART_PULSE,
    HRV_AUTONOMIC,
    AUTONOMIC_STRESS,
    SLEEP_NOCTURNAL,
    BODY_COMPOSITION,
    STEPS_TRAIL,
    ACTIVE_MOVEMENT,
    RECOVERY_SUNRISE,
    RUNNING_STRIDE,
    ALPINE_TRAIL,
    CYCLING_AERO,
    STRENGTH_POWER,
    BODY_FAT,
    BONE_MASS,
    BODY_WATER,
    ECG_SIGNAL
}

enum class MotifIntensity {
    DETAIL_HERO,
    OVERVIEW_CARD,
    CHART_CONTAINER,
    SECTION_BANNER
}

@Immutable
data class MetricVisualAtmosphere(
    val motifKind: MetricMotifKind,
    val atmosphereStyle: AtmosphereStyle,
    val accentDark: Color,
    val accentLight: Color,
    val secondaryAccent: Color,
    val heroTopColor: Color,
    val heroMidColor: Color,
    val heroBottomColor: Color,
    @DrawableRes val artworkRes: Int?
) {
    @Composable
    fun accentColor(): Color = if (isSystemInDarkTheme()) accentDark else accentLight
}

object MetricAtmosphereCatalog {
    val RestingHr = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.HEART_PULSE,
        atmosphereStyle = AtmosphereStyle.HEART_PULSE,
        accentDark = Color(0xFFFB7185),
        accentLight = Color(0xFFE11D48),
        secondaryAccent = Color(0xFFFDA4AF),
        heroTopColor = Color(0xFF1F1019),
        heroMidColor = Color(0xFF2E1624),
        heroBottomColor = Color(0xFF190D15),
        artworkRes = R.drawable.img_motif_heart_real
    )

    val Hrv = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.HRV_AUTONOMIC,
        atmosphereStyle = AtmosphereStyle.HRV_AUTONOMIC,
        accentDark = Color(0xFF2DD4BF),
        accentLight = Color(0xFF0D9488),
        secondaryAccent = Color(0xFF5EEAD4),
        heroTopColor = Color(0xFF0A1E26),
        heroMidColor = Color(0xFF12313C),
        heroBottomColor = Color(0xFF0C222B),
        artworkRes = R.drawable.img_motif_heart_real
    )

    val Stress = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.AUTONOMIC_STRESS,
        atmosphereStyle = AtmosphereStyle.WARM_TERRACOTTA,
        accentDark = Color(0xFFFBBF24),
        accentLight = Color(0xFFD97706),
        secondaryAccent = Color(0xFFFDE68A),
        heroTopColor = Color(0xFF221A17),
        heroMidColor = Color(0xFF33251E),
        heroBottomColor = Color(0xFF1B1513),
        artworkRes = R.drawable.img_motif_brain_stress
    )

    val Sleep = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.SLEEP_NOCTURNAL,
        atmosphereStyle = AtmosphereStyle.SLEEP_NOCTURNAL,
        accentDark = Color(0xFF818CF8),
        accentLight = Color(0xFF4F46E5),
        secondaryAccent = Color(0xFFA5B4FC),
        heroTopColor = Color(0xFF0E1326),
        heroMidColor = Color(0xFF18203C),
        heroBottomColor = Color(0xFF10162B),
        artworkRes = R.drawable.img_motif_sleep_night
    )

    val Weight = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.BODY_COMPOSITION,
        atmosphereStyle = AtmosphereStyle.BODY_SILHOUETTE,
        accentDark = Color(0xFFF59E0B),
        accentLight = Color(0xFFB45309),
        secondaryAccent = Color(0xFFFCD34D),
        heroTopColor = Color(0xFF1E1815),
        heroMidColor = Color(0xFF2D231E),
        heroBottomColor = Color(0xFF191412),
        artworkRes = R.drawable.img_motif_body_silhouette
    )

    val Steps = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.STEPS_TRAIL,
        atmosphereStyle = AtmosphereStyle.ALPINE_DAWN,
        accentDark = Color(0xFF38BDF8),
        accentLight = Color(0xFF0284C7),
        secondaryAccent = Color(0xFF7DD3FC),
        heroTopColor = Color(0xFF0F1E2E),
        heroMidColor = Color(0xFF172E44),
        heroBottomColor = Color(0xFF102133),
        artworkRes = R.drawable.img_motif_steps_trail
    )

    val BodyBattery = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.RECOVERY_SUNRISE,
        atmosphereStyle = AtmosphereStyle.NORDIC_AURORA,
        accentDark = Color(0xFF34D399),
        accentLight = Color(0xFF059669),
        secondaryAccent = Color(0xFF6EE7B7),
        heroTopColor = Color(0xFF0D2224),
        heroMidColor = Color(0xFF143438),
        heroBottomColor = Color(0xFF0F2629),
        artworkRes = R.drawable.img_motif_body_battery
    )

    val BodyFat = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.BODY_FAT,
        atmosphereStyle = AtmosphereStyle.WARM_TERRACOTTA,
        accentDark = Color(0xFFF59E0B),
        accentLight = Color(0xFFD97706),
        secondaryAccent = Color(0xFFFDE68A),
        heroTopColor = Color(0xFF22170F),
        heroMidColor = Color(0xFF332014),
        heroBottomColor = Color(0xFF1B120C),
        artworkRes = R.drawable.img_motif_body_fat
    )

    val BoneMass = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.BONE_MASS,
        atmosphereStyle = AtmosphereStyle.ALPINE_DAWN,
        accentDark = Color(0xFF94A3B8),
        accentLight = Color(0xFF64748B),
        secondaryAccent = Color(0xFFCBD5E1),
        heroTopColor = Color(0xFF131722),
        heroMidColor = Color(0xFF1D2436),
        heroBottomColor = Color(0xFF11151E),
        artworkRes = R.drawable.img_motif_bone_mass
    )

    val BodyWater = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.BODY_WATER,
        atmosphereStyle = AtmosphereStyle.DEEP_FJORD,
        accentDark = Color(0xFF06B6D4),
        accentLight = Color(0xFF0891B2),
        secondaryAccent = Color(0xFF67E8F9),
        heroTopColor = Color(0xFF0B1F2A),
        heroMidColor = Color(0xFF102E3E),
        heroBottomColor = Color(0xFF091A23),
        artworkRes = R.drawable.img_motif_body_water
    )

    val Ecg = MetricVisualAtmosphere(
        motifKind = MetricMotifKind.ECG_SIGNAL,
        atmosphereStyle = AtmosphereStyle.HEART_PULSE,
        accentDark = Color(0xFFF43F5E),
        accentLight = Color(0xFFE11D48),
        secondaryAccent = Color(0xFFFDA4AF),
        heroTopColor = Color(0xFF240E16),
        heroMidColor = Color(0xFF361421),
        heroBottomColor = Color(0xFF1A0A10),
        artworkRes = R.drawable.img_motif_ecg_signal
    )
}

/**
 * Vykreslí procedurální konturu motivu v pravé třetině karty pro okamžitou rozpoznatelnost metriky.
 */
fun DrawScope.drawMetricMotifIllustration(
    motifKind: MetricMotifKind,
    primaryColor: Color,
    secondaryColor: Color,
    alphaMultiplier: Float
) {
    val w = size.width
    val h = size.height
    val cx = w * 0.82f
    val cy = h * 0.46f
    val minDim = minOf(w, h)
    val strokeThin = 1.4.dp.toPx()
    val strokeMed = 2.0.dp.toPx()

    when (motifKind) {
        MetricMotifKind.HEART_PULSE -> {
            listOf(0.22f, 0.36f, 0.52f).forEachIndexed { idx, rFrac ->
                drawCircle(
                    color = primaryColor.copy(alpha = (0.18f - idx * 0.04f) * alphaMultiplier),
                    radius = minDim * rFrac,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokeThin)
                )
            }
            val pulsePath = Path().apply {
                val baseY = h * 0.62f
                moveTo(w * 0.42f, baseY)
                lineTo(w * 0.62f, baseY)
                cubicTo(w * 0.66f, baseY, w * 0.68f, baseY - h * 0.12f, w * 0.71f, baseY)
                lineTo(w * 0.74f, baseY + h * 0.10f)
                lineTo(w * 0.78f, baseY - h * 0.34f)
                lineTo(w * 0.82f, baseY + h * 0.16f)
                cubicTo(w * 0.85f, baseY - h * 0.14f, w * 0.89f, baseY, w * 0.96f, baseY)
            }
            drawPath(
                path = pulsePath,
                color = primaryColor.copy(alpha = 0.28f * alphaMultiplier),
                style = Stroke(width = strokeMed, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        MetricMotifKind.HRV_AUTONOMIC, MetricMotifKind.AUTONOMIC_STRESS -> {
            for (waveIdx in 0..2) {
                val wavePath = Path()
                val phaseShift = waveIdx * 0.9f
                val amp = h * (0.12f + waveIdx * 0.05f)
                val startX = w * 0.45f
                val endX = w * 0.98f
                val steps = 28
                for (i in 0..steps) {
                    val t = i.toFloat() / steps
                    val x = startX + (endX - startX) * t
                    val angle = t * 4.2f + phaseShift
                    val y = cy + sin(angle) * amp * (0.4f + 0.6f * t)
                    if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
                }
                drawPath(
                    path = wavePath,
                    color = (if (waveIdx % 2 == 0) primaryColor else secondaryColor)
                        .copy(alpha = (0.24f - waveIdx * 0.05f) * alphaMultiplier),
                    style = Stroke(width = strokeThin, cap = StrokeCap.Round)
                )
            }
            listOf(Offset(w * 0.72f, cy - h * 0.10f), Offset(w * 0.84f, cy + h * 0.08f), Offset(w * 0.91f, cy - h * 0.06f)).forEach { pt ->
                drawCircle(
                    color = primaryColor.copy(alpha = 0.30f * alphaMultiplier),
                    radius = 3.2.dp.toPx(),
                    center = pt
                )
            }
        }

        MetricMotifKind.SLEEP_NOCTURNAL -> {
            val moonCenter = Offset(w * 0.84f, h * 0.34f)
            val moonRadius = minDim * 0.24f
            drawCircle(
                color = primaryColor.copy(alpha = 0.22f * alphaMultiplier),
                radius = moonRadius,
                center = moonCenter
            )
            drawCircle(
                color = secondaryColor.copy(alpha = 0.16f * alphaMultiplier),
                radius = moonRadius * 1.45f,
                center = moonCenter,
                style = Stroke(width = strokeThin)
            )
            listOf(
                Offset(w * 0.64f, h * 0.24f),
                Offset(w * 0.71f, h * 0.18f),
                Offset(w * 0.77f, h * 0.28f),
                Offset(w * 0.92f, h * 0.22f)
            ).forEachIndexed { idx, star ->
                drawCircle(
                    color = secondaryColor.copy(alpha = (0.32f - idx * 0.04f) * alphaMultiplier),
                    radius = (2.2f + (idx % 2)).dp.toPx(),
                    center = star
                )
            }
            val horizon = Path().apply {
                moveTo(w * 0.40f, h * 0.78f)
                cubicTo(w * 0.58f, h * 0.68f, w * 0.76f, h * 0.84f, w, h * 0.72f)
            }
            drawPath(
                path = horizon,
                color = primaryColor.copy(alpha = 0.22f * alphaMultiplier),
                style = Stroke(width = strokeThin, cap = StrokeCap.Round)
            )
        }

        MetricMotifKind.BODY_COMPOSITION -> {
            val ringCenter = Offset(w * 0.83f, h * 0.48f)
            drawCircle(
                color = primaryColor.copy(alpha = 0.20f * alphaMultiplier),
                radius = minDim * 0.36f,
                center = ringCenter,
                style = Stroke(width = strokeThin)
            )
            drawCircle(
                color = secondaryColor.copy(alpha = 0.15f * alphaMultiplier),
                radius = minDim * 0.24f,
                center = ringCenter,
                style = Stroke(width = strokeThin)
            )
            val leftContour = Path().apply {
                moveTo(w * 0.78f, h * 0.20f)
                cubicTo(w * 0.73f, h * 0.36f, w * 0.76f, h * 0.52f, w * 0.72f, h * 0.80f)
            }
            val rightContour = Path().apply {
                moveTo(w * 0.88f, h * 0.20f)
                cubicTo(w * 0.93f, h * 0.36f, w * 0.90f, h * 0.52f, w * 0.94f, h * 0.80f)
            }
            drawPath(leftContour, color = primaryColor.copy(alpha = 0.28f * alphaMultiplier), style = Stroke(width = strokeMed, cap = StrokeCap.Round))
            drawPath(rightContour, color = secondaryColor.copy(alpha = 0.24f * alphaMultiplier), style = Stroke(width = strokeMed, cap = StrokeCap.Round))
        }

        MetricMotifKind.STEPS_TRAIL, MetricMotifKind.ALPINE_TRAIL -> {
            val ridge = Path().apply {
                moveTo(w * 0.44f, h * 0.82f)
                lineTo(w * 0.62f, h * 0.44f)
                lineTo(w * 0.73f, h * 0.60f)
                lineTo(w * 0.86f, h * 0.28f)
                lineTo(w, h * 0.58f)
            }
            val trail = Path().apply {
                moveTo(w * 0.52f, h * 0.88f)
                cubicTo(w * 0.66f, h * 0.72f, w * 0.74f, h * 0.82f, w * 0.92f, h * 0.56f)
            }
            drawPath(ridge, color = primaryColor.copy(alpha = 0.24f * alphaMultiplier), style = Stroke(width = strokeMed, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(trail, color = secondaryColor.copy(alpha = 0.22f * alphaMultiplier), style = Stroke(width = strokeThin, cap = StrokeCap.Round))
        }

        MetricMotifKind.ACTIVE_MOVEMENT, MetricMotifKind.RUNNING_STRIDE -> {
            for (i in 0..2) {
                val offsetH = i * (h * 0.11f)
                val strideArc = Path().apply {
                    moveTo(w * (0.48f + i * 0.05f), h * 0.78f - offsetH * 0.4f)
                    cubicTo(
                        w * 0.66f, h * 0.32f + offsetH,
                        w * 0.80f, h * 0.76f - offsetH,
                        w * 0.96f, h * 0.26f + offsetH * 0.5f
                    )
                }
                drawPath(
                    path = strideArc,
                    color = (if (i == 0) primaryColor else secondaryColor).copy(alpha = (0.26f - i * 0.06f) * alphaMultiplier),
                    style = Stroke(width = if (i == 0) strokeMed else strokeThin, cap = StrokeCap.Round)
                )
            }
        }

        MetricMotifKind.RECOVERY_SUNRISE -> {
            val sunCenter = Offset(w * 0.82f, h * 0.68f)
            listOf(0.18f, 0.32f, 0.46f).forEachIndexed { idx, rFrac ->
                drawCircle(
                    color = primaryColor.copy(alpha = (0.22f - idx * 0.05f) * alphaMultiplier),
                    radius = minDim * rFrac,
                    center = sunCenter,
                    style = Stroke(width = strokeThin)
                )
            }
            drawLine(
                color = secondaryColor.copy(alpha = 0.24f * alphaMultiplier),
                start = Offset(w * 0.46f, h * 0.68f),
                end = Offset(w * 0.98f, h * 0.68f),
                strokeWidth = strokeThin,
                cap = StrokeCap.Round
            )
        }

        MetricMotifKind.BODY_FAT -> {
            // Hexagonální/kruhová matice lipidových buněk
            listOf(
                Offset(w * 0.82f, h * 0.45f) to minDim * 0.26f,
                Offset(w * 0.70f, h * 0.38f) to minDim * 0.16f,
                Offset(w * 0.74f, h * 0.62f) to minDim * 0.18f,
                Offset(w * 0.92f, h * 0.32f) to minDim * 0.14f,
                Offset(w * 0.94f, h * 0.58f) to minDim * 0.17f
            ).forEachIndexed { idx, (c, r) ->
                drawCircle(
                    color = (if (idx == 0) primaryColor else secondaryColor).copy(alpha = (0.24f - idx * 0.03f) * alphaMultiplier),
                    radius = r,
                    center = c,
                    style = Stroke(width = strokeThin)
                )
            }
        }

        MetricMotifKind.BONE_MASS -> {
            // Krystalická struktura kostní minerální mřížky
            val p1 = Offset(w * 0.72f, h * 0.25f)
            val p2 = Offset(w * 0.88f, h * 0.25f)
            val p3 = Offset(w * 0.94f, h * 0.50f)
            val p4 = Offset(w * 0.85f, h * 0.75f)
            val p5 = Offset(w * 0.68f, h * 0.70f)
            val p6 = Offset(w * 0.64f, h * 0.45f)
            val boneGrid = Path().apply {
                moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); lineTo(p5.x, p5.y); lineTo(p6.x, p6.y); close()
                moveTo(p1.x, p1.y); lineTo(p4.x, p4.y)
                moveTo(p2.x, p2.y); lineTo(p5.x, p5.y)
                moveTo(p3.x, p3.y); lineTo(p6.x, p6.y)
            }
            drawPath(
                path = boneGrid,
                color = primaryColor.copy(alpha = 0.25f * alphaMultiplier),
                style = Stroke(width = strokeThin)
            )
            listOf(p1, p2, p3, p4, p5, p6).forEach { pt ->
                drawCircle(color = secondaryColor.copy(alpha = 0.30f * alphaMultiplier), radius = 2.8.dp.toPx(), center = pt)
            }
        }

        MetricMotifKind.BODY_WATER -> {
            // Vodní vlny a kapky hydratace
            for (idx in 0..2) {
                val wave = Path().apply {
                    val yBase = h * (0.35f + idx * 0.16f)
                    moveTo(w * 0.52f, yBase)
                    cubicTo(w * 0.64f, yBase - h * 0.10f, w * 0.76f, yBase + h * 0.10f, w * 0.88f, yBase - h * 0.06f)
                    cubicTo(w * 0.94f, yBase - h * 0.12f, w * 0.98f, yBase, w, yBase)
                }
                drawPath(
                    path = wave,
                    color = (if (idx == 0) primaryColor else secondaryColor).copy(alpha = (0.26f - idx * 0.06f) * alphaMultiplier),
                    style = Stroke(width = if (idx == 0) strokeMed else strokeThin, cap = StrokeCap.Round)
                )
            }
            listOf(Offset(w * 0.80f, h * 0.26f), Offset(w * 0.90f, h * 0.22f)).forEach { pt ->
                drawCircle(color = secondaryColor.copy(alpha = 0.32f * alphaMultiplier), radius = 3.5.dp.toPx(), center = pt)
            }
        }

        MetricMotifKind.ECG_SIGNAL -> {
            // Ostrý P-Q-R-S-T kardiologický vektorový puls
            val ecgPath = Path().apply {
                val baseY = h * 0.52f
                moveTo(w * 0.44f, baseY)
                lineTo(w * 0.60f, baseY)
                cubicTo(w * 0.63f, baseY, w * 0.65f, baseY - h * 0.08f, w * 0.68f, baseY) // P vlna
                lineTo(w * 0.73f, baseY)
                lineTo(w * 0.76f, baseY + h * 0.08f) // Q
                lineTo(w * 0.81f, baseY - h * 0.42f) // R kmit
                lineTo(w * 0.85f, baseY + h * 0.18f) // S
                lineTo(w * 0.88f, baseY)
                cubicTo(w * 0.91f, baseY - h * 0.12f, w * 0.94f, baseY - h * 0.08f, w * 0.98f, baseY) // T vlna
            }
            drawPath(
                path = ecgPath,
                color = primaryColor.copy(alpha = 0.32f * alphaMultiplier),
                style = Stroke(width = strokeMed, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        else -> {
            // Generický kruh a harmonická linka
            drawCircle(
                color = primaryColor.copy(alpha = 0.20f * alphaMultiplier),
                radius = minDim * 0.30f,
                center = Offset(cx, cy),
                style = Stroke(width = strokeThin)
            )
        }
    }
}
