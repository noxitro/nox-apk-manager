package com.noxitro.apkmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noxitro.apkmanager.ui.AppDetailSheet
import com.noxitro.apkmanager.ui.ConnectionGuideScreen
import com.noxitro.apkmanager.ui.HomeEvent
import com.noxitro.apkmanager.ui.HomeScreen
import com.noxitro.apkmanager.ui.HomeViewModel
import com.noxitro.apkmanager.ui.SettingsScreen
import com.noxitro.apkmanager.ui.theme.NoxApkManagerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    private var canInstall by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoxApkManagerTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                var tab by rememberSaveable { mutableStateOf(0) }
                // 接続方法の画面。どのタブからでも開き、戻る操作で閉じる。
                var showGuide by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showGuide) { showGuide = false }
                val onOpenGuide = { showGuide = true }
                val scope = rememberCoroutineScope()

                // 鍵(JSON)をファイルで選ばせる。スマホ単体で設定するときの入口(PC も adb も要らない)。
                // JSON の MIME はファイルアプリによってまちまちなので絞らず、中身で確かめる。
                val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(viewModel::importKey)
                }
                val onPickKey = { pickKey.launch(arrayOf("*/*")) }
                val onCopied: () -> Unit = { scope.launch { snackbar.showSnackbar("コピーしました") } }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HomeEvent.StartActivity -> runCatching { startActivity(event.intent) }
                                .onFailure { snackbar.showSnackbar("開けませんでした: ${it.message}") }
                            is HomeEvent.Snack -> snackbar.showSnackbar(event.message)
                        }
                    }
                }

                // 幅の狭い端末では下部バー、広い端末(タブレット / Quest の 2D パネル)では
                // 左端のナビゲーションレールになる。切り替えはウィンドウサイズから自動で決まる。
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        item(
                            selected = tab == 0,
                            onClick = { tab = 0; showGuide = false },
                            icon = { Icon(if (tab == 0) Icons.Filled.Apps else Icons.Outlined.Apps, contentDescription = null) },
                            label = { Text("アプリ") },
                        )
                        item(
                            selected = tab == 1,
                            onClick = { tab = 1; showGuide = false },
                            icon = { Icon(if (tab == 1) Icons.Filled.Settings else Icons.Outlined.Settings, contentDescription = null) },
                            label = { Text("設定") },
                        )
                    },
                ) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { inner ->
                    val ld = LocalLayoutDirection.current
                    // 一覧は上端を Scaffold の inset に任せ、下端に少し余白を足す
                    val listPadding = PaddingValues(
                        start = inner.calculateStartPadding(ld),
                        end = inner.calculateEndPadding(ld),
                        top = inner.calculateTopPadding(),
                        bottom = inner.calculateBottomPadding() + 16.dp,
                    )
                    when {
                        showGuide -> ConnectionGuideScreen(
                            state = state,
                            contentPadding = listPadding,
                            canInstallPackages = canInstall,
                            onBack = { showGuide = false },
                            onReload = viewModel::reload,
                            onPickKey = onPickKey,
                            onCopied = onCopied,
                            onOpenInstallPermission = ::openInstallPermission,
                        )
                        tab == 0 -> HomeScreen(
                            state = state,
                            contentPadding = listPadding,
                            onRefresh = viewModel::refresh,
                            onReload = viewModel::reload,
                            onPickKey = onPickKey,
                            onCopied = onCopied,
                            onOpenGuide = onOpenGuide,
                            onFilter = viewModel::setFilter,
                            onUpdateAll = viewModel::updateAll,
                            onCancelBatch = viewModel::cancelBatch,
                            onOpenDetail = viewModel::openDetail,
                            onInstall = { viewModel.install(it) },
                            onOpenApp = viewModel::openApp,
                            onDismissJob = viewModel::dismissJob,
                            onReopenConfirm = viewModel::openPendingConfirm,
                        )
                        else -> SettingsScreen(
                            state = state,
                            contentPadding = listPadding,
                            canInstallPackages = canInstall,
                            onVariant = viewModel::setPreferredVariant,
                            onReload = viewModel::reload,
                            onPickKey = onPickKey,
                            onCopied = onCopied,
                            onOpenGuide = onOpenGuide,
                            onOpenInstallPermission = ::openInstallPermission,
                        )
                    }
                }
                }

                state.detail?.let { entry ->
                    AppDetailSheet(
                        entry = entry,
                        icon = state.icons[entry.project],
                        job = state.jobs[entry.project],
                        onDismiss = viewModel::closeDetail,
                        onInstall = { build -> viewModel.install(entry, build) },
                        onOpenApp = { viewModel.openApp(entry) },
                        onUninstall = { viewModel.uninstall(entry) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canInstall = packageManager.canRequestPackageInstalls()
        // アンインストールや OS 側の操作から戻ったときに、端末の現状だけ引き直す
        viewModel.refreshInstalledStates()
    }

    private fun openInstallPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
