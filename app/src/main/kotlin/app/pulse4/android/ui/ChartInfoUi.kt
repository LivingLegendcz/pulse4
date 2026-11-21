package app.pulse4.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse4.ui.ChartInfo
import app.pulse4.ui.ChartInfoTexts

/**
 * Compact (i) button for charts: same 24 dp badge as [InfoBadgeButton] with a 32 dp hit box so it fits
 * neatly into chart headers without inflating row height or squeezing titles/values.
 */
@Composable
fun ChartInfoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    InfoBadgeButton(
        onClick = onClick,
        modifier = modifier,
        contentDescription = ChartInfoTexts.BUTTON_DESCRIPTION,
        touchTarget = 32.dp,
    )
}

/** Button plus its dialog (state kept here so chart headers only need one line). */
@Composable
internal fun ChartInfoAction(
    info: ChartInfo,
    modifier: Modifier = Modifier,
) {
    var show by remember { mutableStateOf(false) }
    ChartInfoButton(onClick = { show = true }, modifier = modifier)
    if (show) {
        ChartInfoDialog(info, onDismiss = { show = false })
    }
}

/** Concise dialog explaining what the chart represents. */
@Composable
internal fun ChartInfoDialog(
    info: ChartInfo,
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(info.intro, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCBD5E1))
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
