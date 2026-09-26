package com.noxitro.apkmanager.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ビルドの種別。ファイル名の末尾 `-debug.apk` / `-release.apk` に対応する。 */
enum class Variant(val fileSuffix: String) {
    DEBUG("debug"),
    RELEASE("release");

    companion object {
        fun fromSuffix(s: String): Variant? = entries.firstOrNull { it.fileSuffix == s }
    }
}

/**
 * Drive の builds/<project>/meta.json。PC 側の scripts/publish-apk.ps1 か、CI の .github/actions/publish-apk が書く。
 * 無いフォルダもあり得る(古い配布)ので、無ければファイル名からの推定にフォールバックする。
 */
@Serializable
data class ProjectMeta(
    val schema: Int = 1,
    val project: String,
    val packageName: String,
    val label: String? = null,
    val description: String = "",
    val builds: List<BuildMeta> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class BuildMeta(
    val file: String,
    val variant: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long = 0,
    val sha256: String? = null,
    val builtAt: String? = null,
)

/** Drive 上の 1 つの APK。meta.json 由来なら versionCode が入る。 */
data class ApkBuild(
    val driveFileId: String,
    val fileName: String,
    val variant: Variant,
    val versionName: String,
    /** meta.json が無いと分からない。無ければ versionName の比較にフォールバックする。 */
    val versionCode: Long?,
    val sizeBytes: Long,
    val sha256: String?,
    val modifiedTime: String?,
)

/** 端末に入っている側の情報。 */
data class InstalledInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
)

sealed interface AppStatus {
    /** 端末に無い。 */
    data object NotInstalled : AppStatus

    /** Drive の方が新しい。 */
    data class UpdateAvailable(val installed: InstalledInfo, val latest: ApkBuild) : AppStatus

    /** 同じ版。 */
    data class UpToDate(val installed: InstalledInfo) : AppStatus

    /** 端末の方が新しい(手元で直接入れた等)。 */
    data class LocalNewer(val installed: InstalledInfo, val latest: ApkBuild) : AppStatus

    /** package 名が分からず端末と突き合わせられない(meta.json 無し・未ダウンロード)。 */
    data object Unknown : AppStatus
}

/** 一覧の 1 行。 */
data class AppEntry(
    /** builds/ 直下のフォルダ名。 */
    val project: String,
    val folderId: String,
    val packageName: String?,
    val label: String,
    val description: String,
    val builds: List<ApkBuild>,
    /** 表示・インストール対象に選んだビルド(設定の variant 優先度で決める)。 */
    val selected: ApkBuild?,
    val status: AppStatus,
    val hasMeta: Boolean,
    val iconFileId: String?,
    /**
     * この行がマネージャ自身か。自己更新は OS がプロセスを落とすので、
     * 一括更新には混ぜず、行から明示的に実行させる。
     */
    val isSelf: Boolean = false,
) {
    val isUpdateAvailable: Boolean get() = status is AppStatus.UpdateAvailable
    val isInstalled: Boolean get() = status !is AppStatus.NotInstalled && status !is AppStatus.Unknown
}

/**
 * `<name>-<version>-<debug|release>.apk` を分解する。
 * version は「最初に数字で始まるトークン」から variant の手前まで(`0.1.0-phase0` のような版を許す)。
 * 規約外なら null。
 */
fun parseApkFileName(fileName: String): ParsedApkName? {
    if (!fileName.endsWith(".apk", ignoreCase = true)) return null
    val stem = fileName.dropLast(4)
    val tokens = stem.split('-')
    if (tokens.size < 3) return null
    val variant = Variant.fromSuffix(tokens.last().lowercase()) ?: return null
    val body = tokens.dropLast(1)
    val versionStart = body.indexOfFirst { it.firstOrNull()?.isDigit() == true }
    if (versionStart <= 0) return null
    return ParsedApkName(
        name = body.subList(0, versionStart).joinToString("-"),
        versionName = body.subList(versionStart, body.size).joinToString("-"),
        variant = variant,
    )
}

data class ParsedApkName(val name: String, val versionName: String, val variant: Variant)

/**
 * versionName の比較。数値トークンは数値で、それ以外は文字列で比べる。
 * `0.1.0` < `0.2` < `0.10.0`、`0.1.0-phase0` < `0.1.0`(サフィックス付きは先行版扱い)。
 */
fun compareVersionNames(a: String, b: String): Int {
    fun split(v: String): Pair<List<Int>, String> {
        val main = v.substringBefore('-').substringBefore('+')
        val suffix = v.drop(main.length)
        val nums = main.split('.').map { it.toIntOrNull() ?: 0 }
        return nums to suffix
    }
    val (na, sa) = split(a)
    val (nb, sb) = split(b)
    val len = maxOf(na.size, nb.size)
    for (i in 0 until len) {
        val x = na.getOrElse(i) { 0 }
        val y = nb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    // 同じ数字列なら、サフィックス無しが新しい
    return when {
        sa.isEmpty() && sb.isEmpty() -> 0
        sa.isEmpty() -> 1
        sb.isEmpty() -> -1
        else -> sa.compareTo(sb)
    }
}

/** 2 つのビルドのどちらが新しいか。versionCode が両方あればそれを優先する。 */
fun compareBuilds(a: ApkBuild, b: ApkBuild): Int {
    if (a.versionCode != null && b.versionCode != null && a.versionCode != b.versionCode) {
        return a.versionCode.compareTo(b.versionCode)
    }
    return compareVersionNames(a.versionName, b.versionName)
}
