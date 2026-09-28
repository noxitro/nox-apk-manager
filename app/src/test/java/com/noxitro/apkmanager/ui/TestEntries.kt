package com.noxitro.apkmanager.ui

import com.noxitro.apkmanager.model.ApkBuild
import com.noxitro.apkmanager.model.AppEntry
import com.noxitro.apkmanager.model.AppStatus
import com.noxitro.apkmanager.model.InstalledInfo
import com.noxitro.apkmanager.model.Variant

/** 画面のテストで使う行。値は実在の配布フォルダの形に合わせてある。 */
internal fun build(versionName: String = "0.6.0", versionCode: Long = 6) = ApkBuild(
    driveFileId = "file-$versionName",
    fileName = "photo-viewer-$versionName-release.apk",
    variant = Variant.RELEASE,
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = 15_800_000,
    sha256 = null,
    modifiedTime = "2026-09-28T10:00:00Z",
)

/** Drive の方が新しい(「更新あり」の板に入る)行。 */
internal fun updatable(project: String, label: String = project, isSelf: Boolean = false): AppEntry {
    val latest = build()
    return AppEntry(
        project = project,
        folderId = "folder-$project",
        packageName = "com.noxitro.$project",
        label = label,
        description = "$label の説明",
        builds = listOf(latest),
        selected = latest,
        status = AppStatus.UpdateAvailable(InstalledInfo("com.noxitro.$project", "0.5.0", 5), latest),
        hasMeta = true,
        iconFileId = null,
        isSelf = isSelf,
    )
}
