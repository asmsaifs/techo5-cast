package dev.techo5.cast.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small uppercase label above a group of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing()
    }
}

/** A tonal surface card, the one container used everywhere. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        border = BorderStroke(1.dp, if (highlighted) scheme.primary.copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.5f)),
    ) { content() }
}

enum class Tone { Live, Neutral, Warning, Error }

/** A short status label with a dot: "Casting", "Ready", "Not seen". */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (fg, bg) = when (tone) {
        Tone.Live -> scheme.primary to scheme.primary.copy(alpha = 0.14f)
        Tone.Neutral -> scheme.onSurfaceVariant to scheme.surfaceContainerHighest
        Tone.Warning -> scheme.tertiary to scheme.tertiary.copy(alpha = 0.14f)
        Tone.Error -> scheme.error to scheme.error.copy(alpha = 0.14f)
    }
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

