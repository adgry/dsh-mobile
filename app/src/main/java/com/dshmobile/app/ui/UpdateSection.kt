package com.dshmobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshmobile.app.data.AppSettings
import com.dshmobile.app.update.UpdateStatus
import com.dshmobile.app.update.Updater
import com.dshmobile.app.util.formatBytes
import com.dshmobile.app.util.formatTimestamp
import com.dshmobile.app.ui.theme.MonoFamily

/**
 * Self-update controls.
 *
 * The honest caveats are on screen rather than buried: Android always shows its own install
 * confirmation, and a build signed with a different key cannot replace this one.
 */
@Composable
fun UpdateSectionBody(
    updater: Updater,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val status by updater.status.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "当前版本",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
            Text(
                text = "${updater.currentVersionName} (${updater.currentVersionCode})",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                color = scheme.onSurfaceVariant,
            )
        }
        val busy = status is UpdateStatus.Checking || status is UpdateStatus.Downloading
        OutlinedButton(
            onClick = { updater.check(manual = true) },
            enabled = !busy && settings.updateUrl.isNotBlank(),
        ) {
            if (status is UpdateStatus.Checking) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text("检查中")
            } else {
                Text("检查更新")
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = settings.updateUrl,
        onValueChange = { value -> onSettingsChange { it.copy(updateUrl = value.trim()) } },
        label = { Text("更新地址") },
        placeholder = { Text("https://example.com/dsh-mobile/update.json") },
        supportingText = {
            Text("指向一个 update.json，或 GitHub 的 releases/latest 接口。留空则关闭自更新。")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "启动时自动检查",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
            Text(
                text = if (settings.lastUpdateCheck > 0) {
                    "上次检查 ${formatTimestamp(settings.lastUpdateCheck)}，最多一天一次"
                } else {
                    "最多一天一次，有新版本才提示"
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = settings.autoCheckUpdates,
            onCheckedChange = { value -> onSettingsChange { it.copy(autoCheckUpdates = value) } },
        )
    }

    if (!updater.canInstallPackages) {
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = "还需要一次授权",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
            Text(
                text = "系统要求应用先获得「安装未知应用」权限，才能自己装更新。只需授权一次。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = updater::requestInstallPermission) {
                Text("去授权")
            }
        }
    }

    when (val current = status) {
        is UpdateStatus.Available -> UpdateCard(
            title = "有新版本 ${current.manifest.versionName}",
            subtitle = listOfNotNull(
                current.manifest.sizeBytes.takeIf { it > 0 }?.let { formatBytes(it) },
                current.manifest.sha256.takeIf { it.isNotBlank() }?.let { "带校验" },
            ).joinToString(" · "),
            notes = current.manifest.notes,
        ) {
            Button(onClick = { updater.download(current.manifest) }) { Text("下载并安装") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = updater::dismiss) { Text("以后再说") }
        }

        is UpdateStatus.Downloading -> UpdateCard(
            title = "正在下载 ${current.manifest.versionName}",
            subtitle = if (current.totalBytes > 0) {
                "${formatBytes(current.bytesRead)} / ${formatBytes(current.totalBytes)}"
            } else {
                formatBytes(current.bytesRead)
            },
            notes = "",
        ) {
            TextButton(onClick = updater::dismiss) { Text("取消") }
        }

        is UpdateStatus.Ready -> UpdateCard(
            title = "${current.manifest.versionName} 已就绪",
            subtitle = "点安装后由系统确认，安装完会以新版本重启",
            notes = "",
        ) {
            Button(onClick = { updater.install(current.file) }) { Text("立即安装") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = updater::dismiss) { Text("取消") }
        }

        is UpdateStatus.UpToDate -> {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已是最新版本",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }

        is UpdateStatus.Failed -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = updater::dismiss) { Text("知道了") }
            }
        }

        UpdateStatus.Checking, UpdateStatus.Idle -> Unit
    }

    if (status is UpdateStatus.Downloading) {
        val downloading = status as UpdateStatus.Downloading
        Spacer(Modifier.height(8.dp))
        if (downloading.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { downloading.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UpdateCard(
    title: String,
    subtitle: String,
    notes: String,
    actions: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.primaryContainer.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .border(1.dp, scheme.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = notes.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            actions()
        }
    }
}
