package com.siamaehwa.nimstudio.ui.common

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.ReasoningEffort
import androidx.compose.foundation.Image as FoundationImage

@Composable
fun ReasoningChips(
    selected: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReasoningEffort.entries.forEach { effort ->
            FilterChip(
                selected = effort == selected,
                onClick = { onSelect(effort) },
                label = {
                    Text(if (effort == ReasoningEffort.Default) "기본" else effort.label)
                },
            )
        }
    }
}

@Composable
fun AttachmentChips(
    attachments: List<AttachmentContent>,
    loading: Boolean,
    onRemove: (Int) -> Unit,
) {
    if (attachments.isEmpty() && !loading) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        attachments.forEachIndexed { index, attachment ->
            when (attachment) {
                is AttachmentContent.Image -> AttachmentThumb(
                    base64 = attachment.base64,
                    name = attachment.name,
                    size = 56.dp,
                    onRemove = { onRemove(index) },
                )

                is AttachmentContent.Text -> InputChip(
                    selected = false,
                    onClick = { onRemove(index) },
                    label = {
                        Text(
                            attachment.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "첨부 제거",
                            modifier = Modifier.size(14.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun AttachmentPreviews(
    attachments: List<AttachmentContent>,
    modifier: Modifier = Modifier,
    thumbnailSize: Dp = 130.dp,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            when (attachment) {
                is AttachmentContent.Image -> AttachmentThumb(
                    base64 = attachment.base64,
                    name = attachment.name,
                    size = thumbnailSize,
                    onRemove = null,
                )

                is AttachmentContent.Text -> Text(
                    "첨부 · ${attachment.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AttachmentThumb(
    base64: String,
    name: String,
    size: Dp,
    onRemove: (() -> Unit)?,
) {
    Box(modifier = Modifier.size(size)) {
        val bitmap = rememberAttachmentBitmap(base64, (size.value * 2).toInt().coerceAtLeast(64))
        if (bitmap != null) {
            FoundationImage(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = name,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "첨부 제거",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun rememberAttachmentBitmap(base64: String, maxPx: Int): ImageBitmap? =
    remember(base64, maxPx) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx ||
                bounds.outHeight / (sample * 2) >= maxPx
            ) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        }.getOrNull()
    }
