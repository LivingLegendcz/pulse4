package app.pulse4.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse4.ui.ChartInfo
import app.pulse4.ui.ChartInfoTexts

/**
 * (i) button for charts: same badge as the telemetry / metric info buttons ([InfoBadgeButton]) with a 48 dp
 * touch target and the common accessibility label "Vysvětlení grafu".
 */
@Composable
fun ChartInfoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    InfoBadgeButton(
        onClick = onClick,
        modifier = modifier,
        contentDescription = ChartInfoTexts.BUTTON_DESCRIPTION,
        touchTarget = 48.dp,
    )
}

/** Button plus its dialog (state kept here so chart headers only need one line). */
@Composable
internal fun ChartInfoAction(
    info: ChartInfo,
    modifier: Modifier = Modifier,
    seriesColors: Map<String, Color> = emptyMap(),
    seriesStyles: Map<String, SeriesStyle> = emptyMap(),
) {
    var show by remember { mutableStateOf(false) }
    ChartInfoButton(onClick = { show = true }, modifier = modifier)
    if (show) {
        ChartInfoDialog(info, seriesColors, seriesStyles, onDismiss = { show = false })
    }
}

/** Dialog styled like the telemetry info dialog ([TelemetryInfoDialog]). */
@Composable
internal fun ChartInfoDialog(
    info: ChartInfo,
    seriesColors: Map<String, Color> = emptyMap(),
    seriesStyles: Map<String, SeriesStyle> = emptyMap(),
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(ChartInfoTexts.DIALOG_CLOSE, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(info.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.White)
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(info.intro, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                if (info.series.isNotEmpty()) {
                    info.seriesHeading?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFF38BDF8))
                    }
                    info.series.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val color = seriesColors[s.id]
                            when {
                                color != null && seriesStyles[s.id] != null -> SeriesLegendSample(seriesStyles[s.id], color)
                                color != null -> Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                                else -> Text("•", color = Color(0xFF94A3B8))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(s.line(), style = MaterialTheme.typography.bodySmall, color = Color.White)
                        }
                    }
                }
                info.bullets.forEach { b ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(end = 8.dp))
                        Text(b, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
                    }
                }
            }
        },
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(20.dp)
    )
}
