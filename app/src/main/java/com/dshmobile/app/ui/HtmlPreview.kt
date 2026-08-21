package com.dshmobile.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dshmobile.app.ui.components.SmallIconButton
import com.dshmobile.app.util.htmlFileName
import java.io.File

/**
 * A page the model just wrote, rendered.
 *
 * The base URL is a name that resolves to nothing on purpose: it gives the page a real origin, so
 * `localStorage` and canvas work and scripts from a CDN still load, while relative paths — which
 * could only point at this device — resolve to nothing instead of somewhere unexpected. File and
 * content access stay off for the same reason: a generated page has no business reading local files.
 */
private const val PREVIEW_BASE_URL = "https://preview.dsh.local/"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewDialog(html: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var webView by remember { mutableStateOf<WebView?>(null) }
    val title = remember(html) { htmlFileName(html).removeSuffix(".html") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainerHigh)
                    .statusBarsPadding()
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SmallIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "重新加载",
                    tint = scheme.onSurfaceVariant,
                    onClick = { webView?.loadHtml(html) },
                )
                SmallIconButton(
                    icon = Icons.Outlined.OpenInBrowser,
                    contentDescription = "在浏览器打开",
                    tint = scheme.onSurfaceVariant,
                    onClick = { openHtmlExternally(context, html, share = false) },
                )
                SmallIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "分享文件",
                    tint = scheme.onSurfaceVariant,
                    onClick = { openHtmlExternally(context, html, share = true) },
                )
                SmallIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "关闭预览",
                    tint = scheme.onSurfaceVariant,
                    onClick = onDismiss,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // A page written for a desktop width should shrink to fit, not overflow.
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            loadHtml(html)
                            webView = this
                        }
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                        webView = null
                    },
                )
            }
        }
    }
}

private fun WebView.loadHtml(html: String) =
    loadDataWithBaseURL(PREVIEW_BASE_URL, html, "text/html", "utf-8", null)

/**
 * Writes the page to a file and hands it to another app, either to open or to share. This is also
 * how a page leaves the phone: the share sheet is the only route to Files, Drive or a desktop.
 */
private fun openHtmlExternally(context: Context, html: String, share: Boolean) {
    runCatching {
        val dir = File(context.cacheDir, "html").apply { mkdirs() }
        // One file per page, and the previous one is cleared so the cache cannot grow unbounded.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, htmlFileName(html))
        file.writeText(html)

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            if (share) Intent.createChooser(intent, "分享 HTML") else intent,
        )
    }
}
