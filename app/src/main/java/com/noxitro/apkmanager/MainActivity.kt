package com.noxitro.apkmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noxitro.apkmanager.ui.AppDetailSheet
import com.noxitro.apkmanager.ui.HomeEvent
import com.noxitro.apkmanager.ui.HomeScreen
import com.noxitro.apkmanager.ui.HomeViewModel
import com.noxitro.apkmanager.ui.SettingsScreen
import com.noxitro.apkmanager.ui.theme.NoxApkManagerTheme

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
                            onClick = { tab = 0 },
                            icon = { Icon(if (tab == 0) Icons.Filled.Apps else Icons.Outlined.Apps, contentDescription = null) },
                            label = { Text("アプリ") },
                        )
                        item(
                            selected = tab == 1,
                            onClick = { tab = 1 },
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
                    when (tab) {
                        0 -> HomeScreen(
                            state = state,
                            contentPadding = listPadding,
                            onRefresh = viewModel::refresh,
                            onReload = viewModel::reload,
                            onFilter = viewModel::setFilter,
                            onUpdateAll = viewModel::updateAll,
                            onCancelBatch = viewModel::cancelBatch,
                            onOpenDetail = viewModel::openDetail,
                            onInstall = { viewModel.install(it) },
                            onOpenApp = viewModel::openApp,
                            onDismissJob = viewModel::dismissJob,
                            onReopenConfirm = viewModel::openPendingConfirm,
                            onRetry = viewModel::retry,
                            onUninstallFailed = viewModel::uninstallFailed,
                        )
                        else -> SettingsScreen(
                            state = state,
                            contentPadding = listPadding,
                            canInstallPackages = canInstall,
                            onVariant = viewModel::setPreferredVariant,
                            onReload = viewModel::reload,
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
