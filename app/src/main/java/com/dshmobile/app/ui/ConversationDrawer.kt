package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.Conversation
import com.dshmobile.app.ui.components.EmptyState
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.util.formatRelativeDay

@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }

    // Search covers titles and message bodies — the useful thing to find a conversation by is
    // usually something that was said in it, not the auto-generated title.
    val visible = remember(conversations, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            conversations
        } else {
            conversations.filter { conversation ->
                conversation.displayTitle.contains(needle, ignoreCase = true) ||
                    conversation.messages.any { it.content.contains(needle, ignoreCase = true) }
            }
        }
    }

    ModalDrawerSheet(modifier = modifier, drawerContainerColor = scheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DSH Mobile",
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = if (query.isBlank()) {
                            "${conversations.size} 个对话"
                        } else {
                            "${visible.size} / ${conversations.size} 个对话"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                SmallIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings,
                    size = 42.dp,
                    iconSize = 21.dp,
                )
            }

            TextButton(
                onClick = onNew,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建对话", style = MaterialTheme.typography.labelLarge)
            }

            if (conversations.size > 3) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索对话") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            }

            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (query.isBlank()) {
                        EmptyState(
                            icon = Icons.Outlined.ChatBubbleOutline,
                            title = "还没有对话",
                            body = "发一条消息就会自动创建。",
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = "没有匹配的对话",
                            body = "换个关键词试试。",
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = visible, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == activeId,
                            onSelect = { onSelect(conversation.id) },
                            onRename = { onRename(conversation) },
                            onDelete = { onDelete(conversation) },
                            onTogglePin = { onTogglePin(conversation) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    NavigationDrawerItem(
        selected = selected,
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = scheme.primaryContainer.copy(alpha = 0.75f),
        ),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = "已置顶",
                        tint = if (selected) scheme.onPrimaryContainer else scheme.primary,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(14.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.displayTitle,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOfNotNull(
                            formatRelativeDay(conversation.updatedAt).takeIf { it.isNotEmpty() },
                            conversation.messages.size.takeIf { it > 0 }?.let { "$it 条" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            scheme.onPrimaryContainer.copy(alpha = 0.75f)
                        } else {
                            scheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmallIconButton(
                    icon = Icons.Outlined.PushPin,
                    contentDescription = if (conversation.pinned) "取消置顶" else "置顶",
                    onClick = onTogglePin,
                    size = 32.dp,
                    iconSize = 16.dp,
                    tint = when {
                        conversation.pinned && selected -> scheme.onPrimaryContainer
                        conversation.pinned -> scheme.primary
                        selected -> scheme.onPrimaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                )
                SmallIconButton(
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    contentDescription = "重命名",
                    onClick = onRename,
                    size = 32.dp,
                    iconSize = 16.dp,
                    tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
                SmallIconButton(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "删除对话",
                    onClick = onDelete,
                    size = 32.dp,
                    iconSize = 16.dp,
                    tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
            }
        },
    )
}
