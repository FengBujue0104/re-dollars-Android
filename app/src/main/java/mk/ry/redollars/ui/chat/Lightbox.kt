package mk.ry.redollars.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

/**
 * Full-screen image viewer: pinch to zoom (1x–5x), drag to pan when zoomed, double-tap
 * to toggle zoom, single-tap or back to dismiss. Hosted as a Dialog so it overlays the
 * whole app.
 */
@Composable
fun Lightbox(
    url: String,
    onDismiss: () -> Unit,
    onSaveSticker: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        fun reset() {
            scale = 1f
            offset = Offset.Zero
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.97f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale > 1f) reset() else onDismiss() },
                        onDoubleTap = { if (scale > 1f) reset() else scale = 2.5f },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            FilledTonalIconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
            if (onSaveSticker != null || onDownload != null) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onDownload != null) {
                        FilledTonalIconButton(onClick = onDownload) {
                            Icon(DownloadIcon, contentDescription = "Save image to gallery")
                        }
                    }
                    if (onSaveSticker != null) {
                        FilledTonalIconButton(
                            onClick = {
                                onSaveSticker()
                                onDismiss()
                            },
                        ) {
                            Icon(Icons.Filled.Star, contentDescription = "Save to stickers")
                        }
                    }
                }
            }
        }
    }
}

/** Tabler/Material-style download glyph (arrow into a tray); drawn locally because the
 *  bundled material-icons-core set has no Download icon. */
private val DownloadIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 20f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-14f)
            close()
            moveTo(12f, 3f)
            verticalLineToRelative(9.59f)
            lineToRelative(3.29f, -3.3f)
            lineToRelative(1.42f, 1.42f)
            lineToRelative(-5.71f, 5.71f)
            lineToRelative(-5.71f, -5.71f)
            lineToRelative(1.42f, -1.42f)
            lineToRelative(3.29f, 3.3f)
            verticalLineToRelative(-9.59f)
            close()
        }
    }.build()
}
