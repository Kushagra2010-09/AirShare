package app.airshare.ui.components

import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.airshare.connection.FilesSummary
import app.airshare.connection.ProgressUi
import app.airshare.core.Formatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Soft expanding rings. Runs a single float animation, and none at all when inactive or when animations are off. */
@Composable
fun RadarPulse(active: Boolean, modifier: Modifier = Modifier, size: Dp = 220.dp, content: @Composable () -> Unit = {}) {
    val color = MaterialTheme.colorScheme.primary
    val still = reducedMotion()
    val t = if (active && !still) {
        val tr = rememberInfiniteTransition(label = "radar")
        tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "t").value
    } else 0f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val maxR = this.size.minDimension / 2
            val base = if (active) 0.22f else 0.08f
            drawCircle(color.copy(alpha = base), radius = maxR * 0.36f)
            drawCircle(color.copy(alpha = base * 0.6f), radius = maxR * 0.62f, style = Stroke(1.5.dp.toPx()))
            drawCircle(color.copy(alpha = base * 0.4f), radius = maxR * 0.88f, style = Stroke(1.dp.toPx()))
            if (active && !still) for (k in 0..1) {
                val p = (t + k * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = maxR * (0.36f + 0.64f * p), style = Stroke(2.dp.toPx()))
            }
        }
        content()
    }
}

@Composable
fun PhoneGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(width = 26.dp, height = 40.dp)) {
        drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()), style = Stroke(2.dp.toPx()))
        drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 0.08f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.3f, 2.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    }
}

@Composable
fun FileThumb(uri: String, size: Dp = 56.dp) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, uri) {
        if (Build.VERSION.SDK_INT >= 29) {
            value = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.loadThumbnail(Uri.parse(uri), Size(192, 192), null).asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun FilesHeader(files: FilesSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (files.thumbUris.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { files.thumbUris.forEach { FileThumb(it) } }
            Spacer(Modifier.height(12.dp))
        }
        Text(files.kinds.ifBlank { "${files.count} files" }, style = MaterialTheme.typography.titleMedium)
        Text("${files.count} ${if (files.count == 1) "file" else "files"} · ${files.sizeText}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    if (primary) {
        Button(onClick, modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors()) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(onClick, modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(18.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun TransferPanel(title: String, subtitle: String, p: ProgressUi, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val anim by animateFloatAsState(p.fraction, tween(300, easing = FastOutSlowInEasing), label = "p")
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        Text("${(anim * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(p.currentFile, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("File ${p.fileIndex} of ${p.fileCount} · ${Formatting.bytes(p.doneBytes)} of ${Formatting.bytes(p.totalBytes)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Speed / time left only appear once enough data exists; otherwise nothing is shown.
        val extra = listOfNotNull(p.speedBytesPerSec?.let { Formatting.speed(it) }, p.etaSeconds?.let { Formatting.eta(it) }).joinToString(" · ")
        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onCancel != null) { Spacer(Modifier.height(32.dp)); BigButton("Cancel", onCancel, primary = false) }
    }
}

@Composable
fun SuccessBadge() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val s by animateFloatAsState(if (shown) 1f else 0.6f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "s")
    Box(Modifier.size(96.dp).scale(s).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(44.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Pill(text: String, tint: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = tint)
}

@Composable fun HSpace(w: Dp) = Spacer(Modifier.width(w))
