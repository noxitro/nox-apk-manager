package com.noxitro.apkmanager.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.noxitro.apkmanager.drive.DriveApi
import com.noxitro.apkmanager.drive.DriveSource
import com.noxitro.apkmanager.model.ApkBuild
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.model.AppStatus
import com.noxitro.apkmanager.model.InstalledInfo
import com.noxitro.apkmanager.model.ProjectMeta
import com.noxitro.apkmanager.model.Variant
import com.noxitro.apkmanager.model.compareBuilds
import com.noxitro.apkmanager.model.compareVersionNames
import com.noxitro.apkmanager.model.parseApkFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drive の builds/ を読んで、端末のインストール状態と突き合わせた一覧を作る。
 *
 * 1 プロジェクト = builds/ 直下の 1 フォルダ。中の meta.json(あれば)と *.apk を見る。
 * meta.json が無い古いフォルダでも、ファイル名から版と variant を推定して一覧には出す。
 * ただし package 名が分からないので、端末との突き合わせは「一度ダウンロードして覚えた後」になる。
 */
class CatalogRepository(
    private val context: Context,
    private val drive: DriveSource,
    private val prefs: Prefs,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pm: PackageManager get() = context.packageManager

    class BuildsFolderNotFound : Exception("マイドライブ直下に builds フォルダがありません")

    suspend fun loadCatalog(preferred: Variant): List<AppEntry> {
        val root = drive.findBuildsFolder() ?: throw BuildsFolderNotFound()
        val folders = drive.listChildren(root.id).filter { it.mimeType == DriveApi.FOLDER_MIME }
        return coroutineScope {
            folders.map { folder -> async { loadProject(folder, preferred) } }.map { it.await() }
        }.sortedWith(
            // 更新ありを上に、その中では名前順
            compareByDescending<AppEntry> { it.isUpdateAvailable }.thenBy { it.label.lowercase() }
        )
    }

    private suspend fun loadProject(folder: DriveApi.DriveFile, preferred: Variant): AppEntry {
        val children = drive.listChildren(folder.id)
        val metaFile = children.firstOrNull { it.name == META_FILE }
        val iconFile = children.firstOrNull { it.name == ICON_FILE }
        val meta: ProjectMeta? = metaFile?.let { f ->
            runCatching { json.decodeFromString<ProjectMeta>(drive.downloadText(f.id)) }.getOrNull()
        }
        val metaByFile = meta?.builds?.associateBy { it.file }.orEmpty()

        val builds = children
            .filter { it.name.endsWith(".apk", ignoreCase = true) && it.mimeType != DriveApi.FOLDER_MIME }
            .mapNotNull { f ->
                val parsed = parseApkFileName(f.name) ?: return@mapNotNull null
                val m = metaByFile[f.name]
                ApkBuild(
                    driveFileId = f.id,
                    fileName = f.name,
                    variant = m?.let { Variant.fromSuffix(it.variant) } ?: parsed.variant,
                    versionName = m?.versionName ?: parsed.versionName,
                    versionCode = m?.versionCode,
                    sizeBytes = f.size ?: m?.size ?: 0,
                    sha256 = m?.sha256,
                    modifiedTime = f.modifiedTime,
                )
            }

        val packageName = meta?.packageName ?: prefs.packageNameFor(folder.name)
        val selected = selectBuild(builds, preferred)
        val installed = packageName?.let { installedInfo(it) }
        val label = meta?.label
            ?: installed?.let { appLabel(it.packageName) }
            ?: builds.firstOrNull()?.let { parseApkFileName(it.fileName)?.name }
            ?: folder.name

        return AppEntry(
            project = folder.name,
            folderId = folder.id,
            packageName = packageName,
            label = label,
            description = meta?.description.orEmpty(),
            builds = builds.sortedWith(::compareBuilds).reversed(),
            selected = selected,
            status = statusFor(packageName, selected),
            hasMeta = meta != null,
            iconFileId = iconFile?.id,
            isSelf = packageName == context.packageName,
        )
    }

    /** 端末側の現実(PackageManager)だけを読み直して 1 行を作り直す。Drive には行かない。 */
    fun refreshInstalledState(entry: AppEntry, packageName: String? = entry.packageName): AppEntry =
        entry.copy(packageName = packageName, status = statusFor(packageName, entry.selected))

    /** 好みの variant を変えたときに、Drive に行かず選択と状態だけ引き直す。 */
    fun reselect(entry: AppEntry, preferred: Variant): AppEntry {
        val selected = selectBuild(entry.builds, preferred)
        return entry.copy(selected = selected, status = statusFor(entry.packageName, selected))
    }

    private fun statusFor(packageName: String?, selected: ApkBuild?): AppStatus {
        if (packageName == null) return AppStatus.Unknown
        val installed = installedInfo(packageName) ?: return AppStatus.NotInstalled
        if (selected == null) return AppStatus.UpToDate(installed)
        return compareInstalled(installed, selected)
    }

    /** 好みの variant の最新版。無ければもう一方の最新版。 */
    private fun selectBuild(builds: List<ApkBuild>, preferred: Variant): ApkBuild? {
        val ofPreferred = builds.filter { it.variant == preferred }
        val pool = if (ofPreferred.isNotEmpty()) ofPreferred else builds
        return pool.maxWithOrNull(::compareBuilds)
    }

    private fun compareInstalled(installed: InstalledInfo, latest: ApkBuild): AppStatus {
        val cmp = if (latest.versionCode != null) {
            latest.versionCode.compareTo(installed.versionCode)
        } else {
            compareVersionNames(latest.versionName, installed.versionName ?: "0")
        }
        return when {
            cmp > 0 -> AppStatus.UpdateAvailable(installed, latest)
            cmp < 0 -> AppStatus.LocalNewer(installed, latest)
            else -> AppStatus.UpToDate(installed)
        }
    }

    fun installedInfo(packageName: String): InstalledInfo? = try {
        val info = pm.getPackageInfo(packageName, 0)
        InstalledInfo(
            packageName = packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun appLabel(packageName: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /** 端末に入っているアプリのアイコン。入っていなければ null。 */
    fun installedIcon(packageName: String): Drawable? = runCatching {
        pm.getApplicationIcon(packageName)
    }.getOrNull()

    /** ダウンロード済み APK からアイコンと package 名を読む(未インストールの行に使う)。 */
    suspend fun inspectApk(file: File): ApkInspection? = withContext(Dispatchers.IO) {
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return@withContext null
        val app = info.applicationInfo ?: return@withContext null
        // sourceDir を入れないと loadIcon がリソースを見つけられず既定アイコンになる
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        ApkInspection(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull(),
            icon = runCatching { app.loadIcon(pm) }.getOrNull(),
        )
    }

    data class ApkInspection(
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val label: String?,
        val icon: Drawable?,
    )

    /** APK の置き場。cacheDir なので OS が空きを要求すれば消える(消えても再ダウンロードするだけ)。 */
    fun apkCacheFile(build: ApkBuild): File = File(File(context.cacheDir, "apks"), build.fileName)

    /** Drive 上の icon.png。 */
    suspend fun downloadIcon(entry: AppEntry): File? {
        val id = entry.iconFileId ?: return null
        val dest = File(File(context.cacheDir, "icons"), "${entry.project}.png")
        if (dest.exists()) return dest
        return runCatching { drive.downloadToFile(id, dest, null); dest }.getOrNull()
    }

    companion object {
        const val META_FILE = "meta.json"
        const val ICON_FILE = "icon.png"
    }
}
