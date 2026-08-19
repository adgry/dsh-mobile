package com.dshmobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.dshmobile.app.data.Provider
import com.dshmobile.app.ui.components.SmallIconButton

/**
 * Add or edit one OpenAI-compatible service. Testing happens from inside the dialog so a bad URL or
 * key is caught before it is saved and becomes the active service.
 */
@Composable
fun ProviderEditorDialog(
    initial: Provider?,
    testing: Boolean,
    onTest: (Provider) -> Unit,
    onConfirm: (Provider) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }

    val draft = (initial ?: Provider()).copy(
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
    )
    val valid = draft.isUsable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加服务" else "编辑服务") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如 DeepSeek 官方") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    supportingText = { Text("OpenAI 兼容接口，需包含 /v1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        SmallIconButton(
                            icon = if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (keyVisible) "隐藏" else "显示",
                            onClick = { keyVisible = !keyVisible },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onTest(draft) },
                    enabled = valid && !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连接")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = valid) {
                Text("保存", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
