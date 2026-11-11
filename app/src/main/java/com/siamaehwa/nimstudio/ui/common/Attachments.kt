package com.siamaehwa.nimstudio.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.siamaehwa.nimstudio.core.AttachmentContent
import com.siamaehwa.nimstudio.core.ReasoningEffort

@Composable
fun ReasoningPicker(
    selected: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.    Box(modifier = modifier) {
        TextButton(onClick = { open = true }) {
            Text("추론: ${selected.label}", maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "추론 강도 변경")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReasoningEffort.entries.forEach { effort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (effort == ReasoningEffort.Default) {
                                "기본 (모델 설정)"
                            } else {
                                effort.label
                            },
                        )
                    },
                    trailingIcon = {
                        if (effort == selected) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(effort)
                    },
                )
            }
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
            InputChip(
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
                        imageVector = if (attachment is AttachmentContent.Image) {
                            Icons.Filled.Image
                        } else {
                            Icons.Filled.Description
                        },
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
