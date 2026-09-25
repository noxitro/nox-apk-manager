package com.noxitro.apkmanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.noxitro.apkmanager.model.Variant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 端末に残す設定。
 * - 好みの variant(release / debug)。両方あるプロジェクトでどちらを入れるか
 * - プロジェクト → package 名の対応。meta.json が無いフォルダでも、一度ダウンロードした APK から
 *   読んだ package 名を覚えておけば次回から端末と突き合わせられる
 * - Drive を読むサービスアカウントの鍵(JSON)。`adb push` した鍵を取り込んでここに持つ
 */
class Prefs(private val context: Context) {

    val preferredVariant: Flow<Variant> = context.dataStore.data.map { p ->
        p[KEY_VARIANT]?.let { runCatching { Variant.valueOf(it) }.getOrNull() } ?: Variant.RELEASE
    }

    suspend fun setPreferredVariant(v: Variant) {
        context.dataStore.edit { it[KEY_VARIANT] = v.name }
    }

    /** サービスアカウントの鍵(JSON 丸ごと)。未設定なら null。 */
    val serviceAccountKey: Flow<String?> = context.dataStore.data.map { it[KEY_SERVICE_ACCOUNT] }

    suspend fun serviceAccountKeyOnce(): String? = context.dataStore.data.first()[KEY_SERVICE_ACCOUNT]

    suspend fun setServiceAccountKey(json: String) {
        context.dataStore.edit { it[KEY_SERVICE_ACCOUNT] = json }
    }

    suspend fun packageNameFor(project: String): String? =
        context.dataStore.data.first()[packageKey(project)]

    suspend fun rememberPackageName(project: String, packageName: String) {
        context.dataStore.edit { it[packageKey(project)] = packageName }
    }

    private fun packageKey(project: String) = stringPreferencesKey("pkg:$project")

    companion object {
        private val KEY_VARIANT = stringPreferencesKey("preferred_variant")
        private val KEY_SERVICE_ACCOUNT = stringPreferencesKey("service_account_key")
    }
}
