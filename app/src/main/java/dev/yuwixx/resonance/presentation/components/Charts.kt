package dev.yuwixx.resonance.presentation.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    labels: List<String> = emptyList(),
) {
    val maxValue = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    // One 0..1 sweep drives all bars; each bar is offset by its index so they grow left to right.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(700, easing = EmphasizedDecelerate))
    }
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val count = values.size.coerceAtLeast(1)
            val barWidth = size.width / count
            val staggerSpan = 0.4f
            values.forEachIndexed { i, value ->
                val barStart = if (count > 1) (i.toFloat() / count) * staggerSpan else 0f
                val barProgress = ((reveal.value - barStart) / (1f - staggerSpan)).coerceIn(0f, 1f)
                val barH = (value / maxValue) * size.height * barProgress
                val x = i * barWidth
                val w = (barWidth - 4f).coerceAtLeast(1f)
                drawRect(color = trackColor, topLeft = Offset(x + 2f, 0f), size = Size(w, size.height))
                if (barH > 0f) {
                    drawRect(color = barColor, topLeft = Offset(x + 2f, size.height - barH), size = Size(w, barH))
                }
            }
        }
        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                labels.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LineChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.isEmpty()) return
    val fillColor = lineColor.copy(alpha = 0.15f)
    val maxValue = (points.maxOrNull() ?: 1f).coerceAtLeast(1f)
    // Reused between draws to avoid allocating a Path per frame.
    val path = remember { Path() }
    val fillPath = remember { Path() }
    // A clip that grows with `reveal` draws the line in from the left.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(700, easing = EmphasizedDecelerate))
    }
    Canvas(modifier = modifier) {
        val stepX = if (points.size > 1) size.width / (points.size - 1) else size.width
        path.reset()
        fillPath.reset()
        points.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height - (value / maxValue) * size.height
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((points.size - 1) * stepX, size.height)
        fillPath.close()
        clipRect(right = size.width * reveal.value) {
            drawPath(fillPath, color = fillColor)
            drawPath(path, color = lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun donutChartPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.error,
    MaterialTheme.colorScheme.primaryContainer,
    MaterialTheme.colorScheme.secondaryContainer,
)

@Composable
fun DonutChart(
    entries: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    colors: List<Color> = donutChartPalette(),
) {
    val total = entries.sumOf { it.second }.coerceAtLeast(1)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(entries) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(700, easing = EmphasizedDecelerate))
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(120.dp)) {
            var startAngle = -90f
            val strokeWidth = size.minDimension * 0.24f
            entries.forEachIndexed { i, (_, value) ->
                val sweep = 360f * value / total * reveal.value
                drawArc(
                    color = colors[i % colors.size],
                    startAngle = startAngle,
                    sweepAngle = sweep.coerceAtLeast(0.5f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            entries.forEachIndexed { i, (label, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp).staggeredEntrance(i),
                ) {
                    Box(Modifier.size(10.dp).background(colors[i % colors.size], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$label ($value)",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
