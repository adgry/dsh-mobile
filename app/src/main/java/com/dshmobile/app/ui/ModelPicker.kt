package com.dshmobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshmobile.app.data.ModelInfo
import com.dshmobile.app.data.Provider
import com.dshmobile.app.ui.components.EmptyState
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.ui.theme.MonoFamily
import com.dshmobile.app.util.formatTokenBudget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<Provider>,
    modelsByProvider: Map<String, List<ModelInfo>>,
    loading: Boolean,
    error: String?,
    activeProviderId: String,
    currentModel: String,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val total = providers.sumOf { modelsByProvider[it.id]?.size ?: 0 }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "选择模型",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    if (providers.size > 1) {
                        Text(
                            text = "${providers.size} 个服务 · $total 个模型",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(18.dp),
                    )
                } else {
                    SmallIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新模型列表",
                        onClick = onRefresh,
                    )
                }
            }

            if (total == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator(color = scheme.primary)
                        error != null -> EmptyState(
                            icon = Icons.Outlined.CloudOff,
                            title = "拿不到模型列表",
                            body = error,
                        )
                        else -> EmptyState(
                            icon = Icons.Outlined.CloudOff,
                            title = "没有可用模型",
                            body = "到设置里检查服务的 Base URL 与 API Key，然后刷新。",
                        )
                    }
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providers.forEach { provider ->
                    val models = modelsByProvider[provider.id].orEmpty()
                    // With one service configured the grouping header is just noise.
                    if (providers.size > 1) {
                        item(key = "header-${provider.id}") {
                            Row(
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    color = scheme.primary,
                                )
                                if (provider.id == activeProviderId) {
                                    Text(
                                        text = " · 当前",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (models.isEmpty()) {
                        item(key = "empty-${provider.id}") {
                            Text(
                                text = if (provider.isUsable) "未获取到模型" else "未填写 Key",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    items(models, provider, activeProviderId, currentModel, onSelect)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    models: List<ModelInfo>,
    provider: Provider,
    activeProviderId: String,
    currentModel: String,
    onSelect: (String, String) -> Unit,
) {
    models.forEach { model ->
        item(key = "${provider.id}-${model.id}") {
            ModelRow(
                model = model,
                selected = model.id == currentModel && provider.id == activeProviderId,
                onClick = { onSelect(provider.id, model.id) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) scheme.primaryContainer.copy(alpha = 0.6f) else scheme.surfaceContainer,
                RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = MonoFamily,
                ),
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (model.acceptsImages) Badge("看图")
                if (model.hasReasoning) Badge("思考")
                if (model.emitsImages && !model.emitsText) Badge("出图")
                if (model.contextLength > 0) Badge("${formatTokenBudget(model.contextLength)} 上下文")
            }
            if (model.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        scheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = "当前模型",
                tint = scheme.primary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
