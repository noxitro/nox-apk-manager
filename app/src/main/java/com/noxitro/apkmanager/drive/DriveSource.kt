package com.noxitro.apkmanager.drive

import java.io.File

/**
 * 「builds/ フォルダを読む」ための最小の口。
 * 本番は [DriveApi](Google Drive REST)、検証は [LocalDriveSource](端末内フォルダ)。
 */
interface DriveSource {
    suspend fun findBuildsFolder(): DriveApi.DriveFile?
    suspend fun listChildren(folderId: String): List<DriveApi.DriveFile>
    suspend fun downloadText(fileId: String): String
    suspend fun downloadToFile(fileId: String, dest: File, expectedSize: Long?, onProgress: (Float) -> Unit = {})
}

/**
 * 端末内のフォルダを Drive の builds/ に見立てる(debug ビルド専用)。
 * `adb push G:\マイドライブ\builds /sdcard/Android/data/com.noxitro.apkmanager/files/builds`
 * で実物の配布物をそのまま置けば、認証無しで一覧とインストールの流れを通せる。
 * id は root からの相対パス。
 */
class LocalDriveSource(private val root: File) : DriveSource {

    override suspend fun findBuildsFolder(): DriveApi.DriveFile? =
        if (root.isDirectory) toDriveFile(root) else null

    override suspend fun listChildren(folderId: String): List<DriveApi.DriveFile> =
        resolve(folderId).listFiles().orEmpty().sortedBy { it.name }.map { toDriveFile(it) }

    override suspend fun downloadText(fileId: String): String = resolve(fileId).readText()

    override suspend fun downloadToFile(fileId: String, dest: File, expectedSize: Long?, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        resolve(fileId).copyTo(dest, overwrite = true)
        onProgress(1f)
    }

    private fun resolve(id: String): File = if (id.isEmpty()) root else File(root, id)

    private fun toDriveFile(f: File) = DriveApi.DriveFile(
        id = f.relativeTo(root).path.replace('\\', '/'),
        name = f.name,
        mimeType = if (f.isDirectory) DriveApi.FOLDER_MIME else "application/octet-stream",
        size = if (f.isFile) f.length() else null,
        modifiedTime = java.time.Instant.ofEpochMilli(f.lastModified()).toString(),
    )
}
