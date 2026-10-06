package com.noxitro.apkmanager.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * インストールの E2E。**本物の PackageInstaller** にダミーのアプリ(:fixture)を入れさせ、
 * OS の確認画面を UiAutomator で押して、[ApkInstaller] が返す結果を確かめる。
 *
 * 単体テストでは OS 側を偽物にするしかなく、「確認画面を取り消したら何が返るか」
 * 「同じ鍵の更新は本当に確認なしで通るか」は端末でしか確かめられない。
 *
 * エミュレータの OS は素の Android なので、One UI(Galaxy)や HyperOS(Xiaomi)が
 * 独自に挟む画面や保護機能(オートブロッカーなど)はここでは再現しない。
 */
@RunWith(AndroidJUnit4::class)
class InstallE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val installer = ApkInstaller(context)

    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Volatile
    private var host: ComponentActivity? = null

    /** OS が確認画面を要求してきた回数。 */
    private val confirmRequests = AtomicInteger()

    @Before
    fun setUp() {
        // 「提供元不明のアプリ」の許可。実機では利用者が設定画面で一度だけ許可するもの。
        shell("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES allow")
        // OS は、同じインストーラが同じアプリを 30 秒以内に続けて入れると、無確認の更新を止めて
        // 確認画面に戻す(SilentUpdatePolicy)。テストは v1 の数秒後に v2 を入れるので必ず当たる
        // (2026-10-06 に API 35 で実測。外すと確認なしで通る)。実際の更新は何時間も空くので、
        // ここではその間隔の制限だけを外す。無確認で通る条件そのもの(権限・所有権)は外さない。
        shell("pm set-silent-updates-policy --allow-unlimited-silent-updates ${context.packageName}")
        uninstallFixture()
        // 確認画面は前面の Activity から開く。裏からの起動は OS に止められることがある。
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { host = it }
        InstallEvents.onUserAction = { _, intent ->
            confirmRequests.incrementAndGet()
            checkNotNull(host) { "確認画面を開く Activity が無い" }.startActivity(intent)
        }
    }

    @After
    fun tearDown() {
        InstallEvents.onUserAction = null
        scenario.close()
        uninstallFixture()
        shell("pm set-silent-updates-policy --reset")
    }

    @Test
    fun 新規のインストールは確認画面で許可すると入る() {
        val outcome = install("v1", Answer.INSTALL)

        assertTrue("結果: ${outcome.result}", outcome.result is ApkInstaller.Result.Success)
        assertTrue("新規は確認画面が出るはず", outcome.confirmRequests >= 1)
        assertEquals(1L, installedVersionCode())
    }

    /**
     * このアプリが入れたものの更新は、確認なしで通る(UPDATE_PACKAGES_WITHOUT_USER_ACTION と
     * 更新の所有権)。「全て更新」を寝かせておけるのはこれが前提。
     */
    @Test
    fun 自分が入れたアプリの同じ鍵の更新は確認なしで入る() {
        assertTrue(install("v1", Answer.INSTALL).result is ApkInstaller.Result.Success)

        val outcome = install("v2", Answer.INSTALL)

        assertTrue("結果: ${outcome.result}", outcome.result is ApkInstaller.Result.Success)
        assertEquals("更新で確認画面が出た", 0, outcome.confirmRequests)
        assertEquals(2L, installedVersionCode())
    }

    /** 0.7.1 で直したところ。取り消したときに、理由が「キャンセルされました」だけにならない。 */
    @Test
    fun 確認画面で取り消すと理由つきの中止になる() {
        val outcome = install("v1", Answer.CANCEL)

        val aborted = outcome.result as? ApkInstaller.Result.Aborted
            ?: throw AssertionError("中止にならなかった: ${outcome.result}")
        Log.i(TAG, "取り消しの結果: message=${aborted.message} legacy=${aborted.legacyStatus} → ${aborted.description}")
        assertTrue(aborted.description, aborted.description.contains("確認画面で取り消されました"))
        assertNull("入っていないはず", installedVersionCode())
    }

    @Test
    fun 鍵の違う更新は署名違いとして失敗する() {
        assertTrue(install("v1", Answer.INSTALL).result is ApkInstaller.Result.Success)

        val outcome = install("v2alt", Answer.INSTALL)

        val failure = outcome.result as? ApkInstaller.Result.Failure
            ?: throw AssertionError("失敗にならなかった: ${outcome.result}")
        assertTrue("署名違いと読めない: ${failure.message}", failure.isSignatureMismatch)
        assertEquals("元の版が残るはず", 1L, installedVersionCode())
    }

    @Test
    fun 古い版での上書きは巻き戻しとして失敗する() {
        assertTrue(install("v2", Answer.INSTALL).result is ApkInstaller.Result.Success)

        val outcome = install("v1", Answer.INSTALL)

        val failure = outcome.result as? ApkInstaller.Result.Failure
            ?: throw AssertionError("失敗にならなかった: ${outcome.result}")
        assertTrue("巻き戻しと読めない: ${failure.message}", failure.isDowngrade)
        assertEquals("新しい版が残るはず", 2L, installedVersionCode())
    }

    // ── ここから下は道具 ─────────────────────────────────────────

    private enum class Answer { INSTALL, CANCEL }

    private data class Outcome(val result: ApkInstaller.Result, val confirmRequests: Int)

    /**
     * fixture の APK を入れる。確認画面が出たら [answer] のボタンを押す。
     * 出ないこともある(無確認の更新、確認の前に弾かれる失敗)ので、出るとは決めつけずに
     * 「結果が来る」か「ボタンが見える」かの早い方で進める。
     */
    private fun install(flavor: String, answer: Answer): Outcome = runBlocking {
        confirmRequests.set(0)
        val apk = copyFixture(flavor)
        val pending = async(Dispatchers.IO) { installer.install(apk, FIXTURE_PACKAGE) }
        val button = if (answer == Answer.INSTALL) INSTALL_BUTTON else CANCEL_BUTTON
        val deadline = SystemClock.uptimeMillis() + RESULT_TIMEOUT_MS
        while (!pending.isCompleted) {
            if (SystemClock.uptimeMillis() > deadline) {
                pending.cancel()
                throw AssertionError("${RESULT_TIMEOUT_MS / 1000} 秒たっても結果が来ない。画面:\n${dumpScreen()}")
            }
            val found = device.wait(Until.findObject(button), 500) ?: continue
            Log.i(TAG, "確認画面: ${found.text} を押す")
            found.click()
            device.wait(Until.gone(button), 5_000)
        }
        Outcome(pending.await(), confirmRequests.get())
    }

    /** テスト APK の assets から取り出す(端末のファイルでないと PackageInstaller に渡せない)。 */
    private fun copyFixture(flavor: String): File {
        val out = File(context.cacheDir, "fixture-$flavor.apk")
        instrumentation.context.assets.open("fixture/$flavor.apk").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    private fun installedVersionCode(): Long? = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun uninstallFixture() {
        shell("pm uninstall $FIXTURE_PACKAGE")
    }

    /** shell の権限で実行して、終わるまで待つ(出力を読み切らないと終了を待てない)。 */
    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
        }

    private fun dumpScreen(): String = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()

    private companion object {
        const val TAG = "InstallE2ETest"
        const val FIXTURE_PACKAGE = "com.noxitro.apkmanager.fixture"
        const val RESULT_TIMEOUT_MS = 90_000L

        /** 確認画面を出すアプリ。Google の入った端末とそうでない端末で名前が違う。 */
        val INSTALLER_PACKAGE: Pattern = Pattern.compile("com\\.(google\\.)?android\\.packageinstaller")

        /** 確認画面の「インストール」/「更新」(肯定のボタン)。文言は言語で変わるので id で探す。 */
        val INSTALL_BUTTON: BySelector = By.pkg(INSTALLER_PACKAGE).res("android:id/button1")

        /** 確認画面の「キャンセル」。 */
        val CANCEL_BUTTON: BySelector = By.pkg(INSTALLER_PACKAGE).res("android:id/button2")
    }
}
