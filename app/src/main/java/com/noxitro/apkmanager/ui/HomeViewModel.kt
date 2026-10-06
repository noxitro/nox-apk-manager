package com.noxitro.apkmanager.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noxitro.apkmanager.ApkManagerApplication
import com.noxitro.apkmanager.auth.PushedKey
import com.noxitro.apkmanager.auth.ServiceAccountAuth
import com.noxitro.apkmanager.data.CatalogRepository
import com.noxitro.apkmanager.drive.DriveException
import com.noxitro.apkmanager.install.ApkInstaller
import com.noxitro.apkmanager.install.InstallEvents
import com.noxitro.apkmanager.model.ApkBuild
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.model.AppStatus
import com.noxitro.apkmanager.model.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Filter(val label: String) {
    ALL("すべて"),
    UPDATES("更新あり"),
    NOT_INSTALLED("未導入"),
    INSTALLED("導入済み"),
}

/** 1 アプリのインストール作業の進み具合。行の直下に 1 行の実況として出す。 */
data class JobState(
    val stage: Stage,
    val progress: Float = -1f,
    val message: String,
    /** PackageInstaller のセッション。確認画面を開き直すのに使う。 */
    val sessionId: Int? = null,
    /** 入れようとしている版。OS の通知を取りこぼしたときの照合に使う。 */
    val targetPackage: String? = null,
    val targetVersionCode: Long? = null,
    val targetVersionName: String? = null,
    /** 失敗したときに入れようとしていたビルド。「再試行」で同じ版を入れ直すのに使う。 */
    val build: ApkBuild? = null,
    /** 署名不一致・ダウングレードで失敗した。入れ直すにはアンインストールが要る。 */
    val needsUninstall: Boolean = false,
) {
    enum class Stage { DOWNLOADING, INSTALLING, WAITING_USER, DONE, FAILED }
    val isActive: Boolean get() = stage == Stage.DOWNLOADING || stage == Stage.INSTALLING || stage == Stage.WAITING_USER
    /** 確認画面を開き直せるか。 */
    val canReopenConfirm: Boolean get() = stage == Stage.WAITING_USER && sessionId != null
    /** 失敗の行に「アンインストール」を出すか。 */
    val canUninstall: Boolean get() = stage == Stage.FAILED && needsUninstall && targetPackage != null
    /** 失敗の行に「再試行」を出すか。アンインストールが要る失敗は、入れ直しても同じ理由で弾かれるので出さない。 */
    val canRetry: Boolean get() = stage == Stage.FAILED && !needsUninstall && build != null
}

sealed interface SyncState {
    data object Idle : SyncState
    data object Loading : SyncState
    data class Ready(val syncedAtLabel: String) : SyncState
    /**
     * [needsKey] が true なら、サービスアカウントの鍵がまだ端末に入っていない。
     * [needsShare] が true なら、鍵はあるが builds/ がサービスアカウントに共有されていない。
     */
    data class Error(val message: String, val needsKey: Boolean = false, val needsShare: Boolean = false) : SyncState
}

data class HomeUiState(
    val sync: SyncState = SyncState.Idle,
    /** Drive を読んでいるサービスアカウント。鍵が未設定なら null。 */
    val serviceAccountEmail: String? = null,
    val entries: List<AppEntry> = emptyList(),
    val filter: Filter = Filter.ALL,
    val jobs: Map<String, JobState> = emptyMap(),
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val preferredVariant: Variant = Variant.RELEASE,
    val batchRunning: Boolean = false,
    val detail: AppEntry? = null,
) {
    val updates: List<AppEntry> get() = entries.filter { it.isUpdateAvailable }
    val filtered: List<AppEntry> get() = when (filter) {
        Filter.ALL -> entries
        Filter.UPDATES -> updates
        Filter.NOT_INSTALLED -> entries.filter { it.status is AppStatus.NotInstalled || it.status is AppStatus.Unknown }
        Filter.INSTALLED -> entries.filter { it.isInstalled }
    }
}

/** ViewModel から Activity に頼むこと。 */
sealed interface HomeEvent {
    data class StartActivity(val intent: Intent) : HomeEvent
    data class Snack(val message: String) : HomeEvent
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as ApkManagerApplication).container
    private val repo: CatalogRepository get() = container.repository

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private var batchJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(preferredVariant = container.prefs.preferredVariant.first()) }
        }
        // PackageInstaller が OS の確認画面を要求したら Activity に開いてもらう。
        // 直接のコールバックにしてあるのは、Flow の購読が間に合わずに取りこぼすのを避けるため。
        InstallEvents.onUserAction = { _, intent -> _events.tryEmit(HomeEvent.StartActivity(intent)) }
        refresh()
    }

    // ---- 同期 ---------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(sync = SyncState.Loading) }
            try {
                // adb push された鍵があれば先に取り込む(初回だけ動く)
                container.importPushedKeyIfAny()
                _state.update { it.copy(serviceAccountEmail = container.auth.clientEmail()) }
                val entries = repo.loadCatalog(_state.value.preferredVariant)
                _state.update { it.copy(sync = SyncState.Ready(nowLabel()), entries = entries) }
                loadIcons(entries)
            } catch (e: ServiceAccountAuth.MissingKey) {
                val message = missingKeyMessage(e.message.orEmpty(), container.lastKeyImport)
                _state.update { it.copy(sync = SyncState.Error(message, needsKey = true)) }
            } catch (e: CatalogRepository.BuildsFolderNotFound) {
                // サービスアカウントから builds が見えない = 共有していないことが多い(名前違いもあり得る)
                val error = if (container.isLocalSource) {
                    SyncState.Error(e.message.orEmpty())
                } else {
                    SyncState.Error(
                        "builds フォルダが見えません。サービスアカウントに共有されていないか、フォルダ名が builds ではありません",
                        needsShare = true,
                    )
                }
                _state.update { it.copy(sync = error) }
            } catch (e: DriveException) {
                // 403 は Drive API が無効なときにも出る。共有の問題と取り違えないよう先に見分ける。
                val error = when {
                    e.isApiDisabled -> SyncState.Error(
                        "Drive API が有効になっていません。Google Cloud で、鍵を作ったプロジェクトの Google Drive API を有効にしてください",
                    )
                    e.code == 403 -> SyncState.Error(
                        "builds/ を読めません(HTTP 403)。Drive の builds/ をサービスアカウントに共有してください",
                        needsShare = true,
                    )
                    else -> SyncState.Error("Drive の読み取りに失敗しました(HTTP ${e.code})")
                }
                _state.update { it.copy(sync = error) }
            } catch (e: IOException) {
                _state.update { it.copy(sync = SyncState.Error("通信に失敗しました: ${e.message ?: e.javaClass.simpleName}")) }
            } catch (e: Exception) {
                _state.update { it.copy(sync = SyncState.Error("読み込みに失敗しました: ${e.message ?: e.javaClass.simpleName}")) }
            }
        }
    }

    /**
     * 鍵が無いときに、push した鍵がなぜ取り込めなかったかを添える。
     * 「未設定」だけでは、push していないのか・場所や名前が違うのか・中身が違うのかが分からない。
     */
    private fun missingKeyMessage(base: String, pushed: PushedKey.Result?): String = when (pushed) {
        is PushedKey.Result.Invalid -> "${pushed.file.name} を取り込めませんでした: ${pushed.reason}"
        is PushedKey.Result.NotFound -> buildString {
            append(base)
            val dir = pushed.dir
            if (dir == null) {
                append("\n端末のストレージを読めません(外部ストレージが使えない状態です)")
            } else {
                append("\n探した場所: ").append(dir.path)
                append(
                    if (pushed.others.isEmpty()) "(空)"
                    else "\nあったもの: " + pushed.others.take(8).joinToString(", "),
                )
            }
        }
        is PushedKey.Result.Found, null -> base
    }

    /**
     * ファイル選択で選ばれた鍵を取り込む(スマホ単体での設定。PC も adb も要らない)。
     *
     * 取り込めたら、端末内に置かれた元ファイル(ダウンロード等)は消す。秘密鍵を共有ストレージに残さないため
     * (adb push の取り込みと同じ方針)。クラウド上のファイル(Drive 等)は唯一の控えかもしれないので消さない。
     */
    fun importKey(uri: Uri) {
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                _events.tryEmit(HomeEvent.Snack("選んだファイルを読めませんでした"))
                return@launch
            }
            val json = PushedKey.parse(bytes).getOrElse {
                _events.tryEmit(HomeEvent.Snack("鍵として使えません: ${it.message}"))
                return@launch
            }
            container.prefs.setServiceAccountKey(json)
            container.auth.invalidate()
            val deleted = uri.authority in LOCAL_DOCUMENT_AUTHORITIES && withContext(Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            }
            _events.tryEmit(
                HomeEvent.Snack(
                    if (deleted) "鍵を取り込みました(元のファイルは消しました)"
                    else "鍵を取り込みました。元のファイルは不要なので消しておいてください",
                ),
            )
            refresh()
        }
    }

    /** 鍵を入れ直した / 共有し直した後に、もう一度読む。 */
    fun reload() = refresh()

    private suspend fun loadIcons(entries: List<AppEntry>) {
        for (entry in entries) {
            if (_state.value.icons.containsKey(entry.project)) continue
            val bitmap = withContext(Dispatchers.IO) {
                entry.packageName?.let { repo.installedIcon(it) }?.toBitmap(96, 96)?.asImageBitmap()
                    ?: repo.downloadIcon(entry)?.let { f ->
                        android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
                    }
            } ?: continue
            _state.update { it.copy(icons = it.icons + (entry.project to bitmap)) }
        }
    }

    // ---- 画面操作 ------------------------------------------------------------

    fun setFilter(filter: Filter) = _state.update { it.copy(filter = filter) }

    fun openDetail(entry: AppEntry) = _state.update { it.copy(detail = entry) }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    fun setPreferredVariant(variant: Variant) {
        viewModelScope.launch {
            container.prefs.setPreferredVariant(variant)
            _state.update { s ->
                s.copy(
                    preferredVariant = variant,
                    entries = s.entries.map { repo.reselect(it, variant) },
                    detail = s.detail?.let { repo.reselect(it, variant) },
                )
            }
        }
    }

    fun openApp(entry: AppEntry) {
        val pkg = entry.packageName ?: return
        val intent = getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            _events.tryEmit(HomeEvent.Snack("${entry.label} は起動できる画面を持っていません"))
            return
        }
        _events.tryEmit(HomeEvent.StartActivity(intent))
    }

    fun uninstall(entry: AppEntry) {
        uninstallPackage(entry.packageName ?: return)
    }

    /** 失敗した行の「アンインストール」。APK から読んだ package 名を使う(meta.json が無い行でも効くように)。 */
    fun uninstallFailed(project: String) {
        uninstallPackage(_state.value.jobs[project]?.targetPackage ?: return)
    }

    private fun uninstallPackage(pkg: String) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
        _events.tryEmit(HomeEvent.StartActivity(intent))
    }

    /**
     * 失敗した行の「再試行」。前回と同じビルドを入れ直す。
     *
     * ただし、一覧を読み直してそのビルドが Drive から消えた、あるいは設定で variant を切り替えて
     * 行が別の variant を指すようになったときは、行に出ているビルド([AppEntry.selected])を入れる。
     * 行に書いてあるものと違う物を入れないため。
     */
    fun retry(project: String) {
        val s = _state.value
        val entry = s.entries.firstOrNull { it.project == project }
        if (entry == null) {
            _events.tryEmit(HomeEvent.Snack("$project は Drive の builds/ に見当たりません。一覧を読み直してください"))
            return
        }
        val previous = s.jobs[project]?.build
        val stillValid = previous != null &&
            entry.builds.any { it.driveFileId == previous.driveFileId } &&
            previous.variant == entry.selected?.variant
        install(entry, if (stillValid) previous else entry.selected)
    }

    /**
     * 端末側が変わった(アンインストール等)後に、Drive に行かず状態だけ引き直す。
     *
     * あわせて、進行中の作業を端末の現実と突き合わせる。OS の完了通知を取りこぼしても
     * 「入っている版が目標と一致していれば完了」と判定できるので、待ち続けたままにならない。
     */
    fun refreshInstalledStates() {
        _state.update { s ->
            val entries = s.entries.map { repo.refreshInstalledState(it) }
            // アンインストールが要る失敗は、アンインストールが済んだら役目を終えるので畳む。
            // 行は「導入」に戻るので、そこから入れ直せる。
            val jobs = s.jobs.filterValues { job ->
                !(job.needsUninstall && job.targetPackage != null && repo.installedInfo(job.targetPackage) == null)
            }.mapValues { (_, job) ->
                if (!job.isActive || job.targetPackage == null) return@mapValues job
                val installed = repo.installedInfo(job.targetPackage) ?: return@mapValues job
                val done = job.targetVersionCode?.let { installed.versionCode >= it }
                    ?: (installed.versionName == job.targetVersionName)
                if (done) {
                    JobState(JobState.Stage.DONE, 1f, "${job.targetVersionName ?: ""} を入れました".trim())
                } else {
                    job
                }
            }
            s.copy(entries = entries, jobs = jobs, detail = s.detail?.let { repo.refreshInstalledState(it) })
        }
    }

    /**
     * OS の確認画面をもう一度開く。
     * バックグラウンドからの Activity 起動が OS に弾かれると画面が出ないまま待ちになるため、
     * 行から開き直せるようにしてある。
     */
    fun openPendingConfirm(project: String) {
        val job = _state.value.jobs[project] ?: return
        val sessionId = job.sessionId ?: return
        val intent = container.installer.confirmIntentFor(sessionId)
        if (intent == null) {
            _events.tryEmit(HomeEvent.Snack("確認画面をもう一度開けません。更新を押し直してください"))
            return
        }
        _events.tryEmit(HomeEvent.StartActivity(intent))
    }

    // ---- インストール ---------------------------------------------------------

    fun install(entry: AppEntry, build: ApkBuild? = entry.selected) {
        val target = build ?: run {
            _events.tryEmit(HomeEvent.Snack("${entry.label} には入れられる APK がありません"))
            return
        }
        if (_state.value.jobs[entry.project]?.isActive == true) return
        viewModelScope.launch { runInstall(entry, target) }
    }

    fun updateAll() {
        if (batchJob?.isActive == true) return
        batchJob = viewModelScope.launch {
            _state.update { it.copy(batchRunning = true) }
            try {
                // 1 件ずつ。OS の確認ダイアログが同時に複数出ると取り違えるため並列にしない。
                //
                // 自分自身は混ぜない。自己更新は OS がこのプロセスを落とすので、
                // 一括の途中で実行すると残りの更新が黙って行われないまま終わる。
                val all = _state.value.updates
                val (self, others) = all.partition { it.isSelf }
                for (entry in others) {
                    // 行の「更新」や「再試行」で既に入れている最中の行には重ねない
                    if (_state.value.jobs[entry.project]?.isActive == true) continue
                    val build = entry.selected ?: continue
                    runInstall(entry, build)
                }
                if (self.isNotEmpty()) {
                    _events.tryEmit(
                        HomeEvent.Snack("Nox APK Manager 自身の更新は行の「更新」から実行してください(更新するとアプリが一度終了します)"),
                    )
                }
            } finally {
                _state.update { it.copy(batchRunning = false) }
            }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
    }

    fun dismissJob(project: String) = _state.update { it.copy(jobs = it.jobs - project) }

    private suspend fun runInstall(entry: AppEntry, build: ApkBuild) {
        val project = entry.project
        fun setJob(job: JobState) = _state.update { it.copy(jobs = it.jobs + (project to job)) }
        // 失敗には入れようとしたビルドと package 名を持たせる(行から「再試行」「アンインストール」するため)。
        var failedPackage: String? = entry.packageName
        fun fail(message: String, needsUninstall: Boolean = false) = setJob(
            JobState(
                JobState.Stage.FAILED,
                message = message,
                targetPackage = failedPackage,
                build = build,
                needsUninstall = needsUninstall,
            ),
        )

        try {
            val file = repo.apkCacheFile(build)
            val sizeLabel = formatSize(build.sizeBytes)
            setJob(JobState(JobState.Stage.DOWNLOADING, 0f, "ダウンロード 0 / $sizeLabel"))
            try {
                downloadWithRetry(build, file) { p ->
                    val done = if (p >= 0) formatSize((p * build.sizeBytes).toLong()) else "…"
                    setJob(JobState(JobState.Stage.DOWNLOADING, p, "ダウンロード $done / $sizeLabel"))
                }
            } catch (e: ServiceAccountAuth.MissingKey) {
                fail(e.message.orEmpty())
                return
            } catch (e: IOException) {
                fail("ダウンロードに失敗: ${e.message ?: e.javaClass.simpleName}")
                return
            }

            // 落とした APK 自身から package 名を確かめる。meta.json と食い違えば入れない。
            val inspection = repo.inspectApk(file)
            if (inspection == null) {
                fail("APK として読めませんでした(ファイルが壊れている可能性)")
                return
            }
            if (entry.packageName != null && entry.packageName != inspection.packageName) {
                fail("meta.json の package(${entry.packageName})と APK の package(${inspection.packageName})が違います")
                return
            }
            failedPackage = inspection.packageName
            if (entry.packageName == null) {
                container.prefs.rememberPackageName(project, inspection.packageName)
            }
            inspection.icon?.let { d ->
                val bmp = d.toBitmap(96, 96).asImageBitmap()
                _state.update { it.copy(icons = it.icons + (project to bmp)) }
            }

            // 以降の JobState には目標の版を持たせる。OS の完了通知を取りこぼしても
            // 端末の現実と突き合わせて完了と判定できるようにするため(refreshInstalledStates)。
            fun stage(s: JobState.Stage, progress: Float, message: String, sessionId: Int? = null) = setJob(
                JobState(
                    stage = s,
                    progress = progress,
                    message = message,
                    sessionId = sessionId ?: _state.value.jobs[project]?.sessionId,
                    targetPackage = inspection.packageName,
                    targetVersionCode = build.versionCode ?: inspection.versionCode,
                    targetVersionName = build.versionName,
                ),
            )

            // 自分自身を入れ替えると OS がこのプロセスを落とす。結果表示までは辿り着けないので、
            // 先に何が起きるかを書いておく。再起動後は端末の版を読み直して「最新」になる。
            val installingMessage =
                if (entry.isSelf) "インストール中…完了するとこのアプリは終了します" else "インストール中…"
            stage(JobState.Stage.INSTALLING, -1f, installingMessage)
            // 確認画面を要求されたら「OS の確認待ち」に切り替える。
            // このセッションの分だけを見る(一括更新で他の行に取り違えないように)。
            var mySession = -1
            val previous = InstallEvents.onUserAction
            InstallEvents.onUserAction = { sessionId, intent ->
                previous?.invoke(sessionId, intent)
                if (sessionId == mySession) {
                    stage(
                        JobState.Stage.WAITING_USER,
                        -1f,
                        if (entry.isSelf) {
                            "OS の確認待ち。「インストール」を押すとこのアプリは終了します"
                        } else {
                            "OS の確認待ち。ダイアログで「インストール」を押してください"
                        },
                    )
                }
            }
            val result = try {
                container.installer.install(file, inspection.packageName) { sessionId ->
                    mySession = sessionId
                    stage(JobState.Stage.INSTALLING, -1f, "インストール中…", sessionId = sessionId)
                }
            } finally {
                InstallEvents.onUserAction = previous
            }

            when (result) {
                is ApkInstaller.Result.Success -> {
                    setJob(JobState(JobState.Stage.DONE, 1f, "${build.versionName} を入れました"))
                    _state.update { s ->
                        s.copy(
                            entries = s.entries.map { if (it.project == project) repo.refreshInstalledState(it, inspection.packageName) else it },
                            detail = s.detail?.takeIf { it.project == project }?.let { repo.refreshInstalledState(it, inspection.packageName) } ?: s.detail,
                        )
                    }
                    file.delete()
                }
                // 理由ごと出す。「キャンセルされました」だけでは、押した覚えが無いときに直しようが無い。
                is ApkInstaller.Result.Aborted ->
                    fail(result.description)
                is ApkInstaller.Result.Failure -> {
                    val needsUninstall = result.isSignatureMismatch || result.isDowngrade
                    val reason = when {
                        result.isSignatureMismatch ->
                            "署名が違うので上書きできません。いったんアンインストールしてから入れ直してください(debug と release の取り違えか、鍵の変更)"
                        result.isDowngrade ->
                            "端末に入っている版の方が新しいので入れられません。戻すならアンインストールが必要です"
                        else -> "インストールに失敗: ${result.message.ifBlank { "code ${result.status}" }}"
                    }
                    fail(reason, needsUninstall)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            fail("中断しました")
            throw e
        } catch (e: Exception) {
            fail("失敗: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** トークンの期限切れ(401/403)は [com.noxitro.apkmanager.drive.DriveApi] が自分で 1 回やり直す。 */
    private suspend fun downloadWithRetry(build: ApkBuild, file: java.io.File, onProgress: (Float) -> Unit) {
        container.drive.downloadToFile(build.driveFileId, file, build.sizeBytes.takeIf { it > 0 }, onProgress)
    }

    private fun nowLabel(): String = SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date())
}

/** 端末内のファイルを指す DocumentsProvider。ここから選んだ鍵は取り込み後に消す。 */
private val LOCAL_DOCUMENT_AUTHORITIES = setOf(
    "com.android.providers.downloads.documents",
    "com.android.externalstorage.documents",
    "com.android.providers.media.documents",
)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.JAPAN, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.JAPAN, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
