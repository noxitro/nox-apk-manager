package com.noxitro.apkmanager.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.ui.theme.NoxApkManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ホームの行と「更新あり」の板。エミュレータを使わず、Robolectric で JVM 上に描いて確かめる。
 * 見るのは「何が出るか・押すと何が呼ばれるか」だけで、見た目(色や余白)は見ない。
 */
@RunWith(RobolectricTestRunner::class)
class UpdateBoardTest {

    @get:Rule
    val compose = createComposeRule()

    private val retried = mutableListOf<String>()
    private val uninstalled = mutableListOf<String>()

    private fun show(entries: List<AppEntry>, jobs: Map<String, JobState> = emptyMap(), batchRunning: Boolean = false) {
        val state = HomeUiState(
            sync = SyncState.Ready("12:00"),
            entries = entries,
            jobs = jobs,
            batchRunning = batchRunning,
        )
        compose.setContent {
            NoxApkManagerTheme {
                HomeScreen(
                    state = state,
                    contentPadding = PaddingValues(),
                    onRefresh = {},
                    onPickKey = {},
                    onCopied = {},
                    onOpenGuide = {},
                    onReload = {},
                    onFilter = {},
                    onUpdateAll = {},
                    onCancelBatch = {},
                    onOpenDetail = {},
                    onInstall = {},
                    onOpenApp = {},
                    onDismissJob = {},
                    onReopenConfirm = {},
                    onRetry = { retried += it },
                    onUninstallFailed = { uninstalled += it },
                )
            }
        }
    }

    private fun failed(needsUninstall: Boolean, project: String = "photo") = JobState(
        stage = JobState.Stage.FAILED,
        message = if (needsUninstall) "署名が違うので上書きできません" else "ダウンロードに失敗: timeout",
        targetPackage = "com.noxitro.$project",
        build = testBuild(),
        needsUninstall = needsUninstall,
    )

    @Test
    fun `署名不一致で失敗した行にはアンインストールが出て、押すとその行を渡す`() {
        show(listOf(updatable("photo")), jobs = mapOf("photo" to failed(needsUninstall = true)))

        compose.onNodeWithText("再試行").assertDoesNotExist()
        compose.onNodeWithText("アンインストール").performClick()

        assertEquals(listOf("photo"), uninstalled)
    }

    @Test
    fun `通信で失敗した行には再試行が出て、押すとその行を渡す`() {
        show(listOf(updatable("photo")), jobs = mapOf("photo" to failed(needsUninstall = false)))

        compose.onNodeWithText("アンインストール").assertDoesNotExist()
        compose.onNodeWithText("再試行").performClick()

        assertEquals(listOf("photo"), retried)
    }

    @Test
    fun `一括更新の最中は失敗した行のボタンを押せない`() {
        show(
            listOf(updatable("photo"), updatable("camera")),
            jobs = mapOf(
                "photo" to failed(needsUninstall = false),
                "camera" to failed(needsUninstall = true, project = "camera"),
            ),
            batchRunning = true,
        )

        compose.onNodeWithText("再試行").assertIsNotEnabled()
        compose.onNodeWithText("アンインストール").assertIsNotEnabled()
    }

    @Test
    fun `自分自身の行には更新で終了することを、板には個別に更新することを書き添える`() {
        show(listOf(updatable("photo"), updatable("apkmanager", label = "Nox APK Manager", isSelf = true)))

        compose.onNodeWithText("更新するとこのアプリは一度終了します").assertExists()
        compose.onNodeWithText("(うち 1 件は個別に更新)", substring = true).assertExists()
        compose.onNodeWithText("全て更新").assertIsEnabled()
    }

    @Test
    fun `更新が自分自身だけなら全て更新を出さない`() {
        show(listOf(updatable("apkmanager", label = "Nox APK Manager", isSelf = true)))

        compose.onNodeWithText("全て更新").assertDoesNotExist()
        // 行の「更新」からは入れられる(行は 1 つなので「更新」ボタンも 1 つ)
        compose.onAllNodesWithText("更新").assertCountEquals(1)
        compose.onNodeWithText("更新").assertIsEnabled()
        compose.onNodeWithText("個別に更新)", substring = true).assertDoesNotExist()
    }

    @Test
    fun `自分自身が無ければ件数に注記を付けない`() {
        show(listOf(updatable("photo"), updatable("camera")))

        compose.onNodeWithText("2 件", substring = true).assertExists()
        compose.onNodeWithText("個別に更新", substring = true).assertDoesNotExist()
        compose.onNodeWithText("全て更新").assertIsEnabled()
    }
}
