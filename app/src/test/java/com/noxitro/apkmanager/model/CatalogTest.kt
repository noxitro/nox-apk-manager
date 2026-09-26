package com.noxitro.apkmanager.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun parsesConventionalNames() {
        assertEquals(ParsedApkName("photo-viewer", "0.6.0", Variant.RELEASE), parseApkFileName("photo-viewer-0.6.0-release.apk"))
        assertEquals(ParsedApkName("LaunchDrawer", "0.2", Variant.DEBUG), parseApkFileName("LaunchDrawer-0.2-debug.apk"))
        assertEquals(ParsedApkName("clipboard", "1.0", Variant.DEBUG), parseApkFileName("clipboard-1.0-debug.apk"))
    }

    @Test
    fun keepsPreReleaseSuffixInsideVersion() {
        // silent-camera の versionName "0.1.0-phase0" のように、版の中にハイフンがあってもよい
        assertEquals(ParsedApkName("silent-camera", "0.1.0-phase0", Variant.DEBUG), parseApkFileName("silent-camera-0.1.0-phase0-debug.apk"))
    }

    @Test
    fun rejectsNamesOutsideTheConvention() {
        assertNull(parseApkFileName("app-debug.apk"))                    // 版が無い
        assertNull(parseApkFileName("sample-app-debug.apk"))             // 版が無い
        assertNull(parseApkFileName("manualrotate-0.1.0-debug-20260730-0251.apk")) // 末尾が variant でない
        assertNull(parseApkFileName("meta.json"))
        assertNull(parseApkFileName("0.1.0-debug.apk"))                  // 名前が無い
    }

    @Test
    fun comparesVersionNamesNumerically() {
        assertTrue(compareVersionNames("0.10.0", "0.9.0") > 0)
        assertTrue(compareVersionNames("0.2", "0.1.9") > 0)
        assertEquals(0, compareVersionNames("1.0", "1.0.0"))
        assertTrue(compareVersionNames("0.1.0-phase0", "0.1.0") < 0)
        assertTrue(compareVersionNames("0.1.0", "0.1.0-phase0") > 0)
    }

    @Test
    fun versionCodeWinsWhenBothKnown() {
        val a = build("0.6.0", 6)
        val b = build("0.5.9", 7) // 番号は古く見えるが code は新しい
        assertTrue(compareBuilds(b, a) > 0)
    }

    @Test
    fun fallsBackToVersionNameWhenCodeMissing() {
        val a = build("0.6.0", null)
        val b = build("0.5.9", 7)
        assertTrue(compareBuilds(a, b) > 0)
    }

    private fun build(version: String, code: Long?) = ApkBuild(
        driveFileId = "id-$version",
        fileName = "x-$version-release.apk",
        variant = Variant.RELEASE,
        versionName = version,
        versionCode = code,
        sizeBytes = 1,
        sha256 = null,
        modifiedTime = null,
    )
}
