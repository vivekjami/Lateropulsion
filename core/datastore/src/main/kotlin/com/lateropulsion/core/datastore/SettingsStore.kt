package com.lateropulsion.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Device-local settings that are not clinical data. Lives in app-private storage; contains no PHI. */
public data class Settings(
    val siteName: String = "",
    val deviceProfileId: String? = null,
    val headsetProfileId: String? = null,
    val ipdMm: Double? = null,
    val researchMode: Boolean = false,
    val autoLockSeconds: Int = 120,
    val onboardingDone: Boolean = false,
    val fieldCalibratedSign: Int = 0,
    val fieldCalibratedMountDeg: Double? = null,
    val fieldCalibratedAt: Long? = null,
    val lastRetentionCheckAt: Long = 0L,
    val trainingModeEnabled: Boolean = true,
    val language: String = "en",
)

private val Context.lpDataStore: DataStore<Preferences> by preferencesDataStore(name = "lateropulsion_settings")

public class SettingsStore(context: Context) {
    private val ds = context.applicationContext.lpDataStore

    public val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            siteName = p[SITE] ?: "",
            deviceProfileId = p[DEVICE],
            headsetProfileId = p[HEADSET],
            ipdMm = p[IPD],
            researchMode = p[RESEARCH] ?: false,
            autoLockSeconds = p[AUTO_LOCK] ?: 120,
            onboardingDone = p[ONBOARDED] ?: false,
            fieldCalibratedSign = p[FIELD_SIGN] ?: 0,
            fieldCalibratedMountDeg = p[FIELD_MOUNT],
            fieldCalibratedAt = p[FIELD_AT],
            lastRetentionCheckAt = p[RETENTION_AT] ?: 0L,
            trainingModeEnabled = p[TRAINING] ?: true,
            language = p[LANG] ?: "en",
        )
    }

    public suspend fun current(): Settings = settings.first()

    public suspend fun update(block: (Settings) -> Settings) {
        val next = block(current())
        ds.edit { p ->
            p[SITE] = next.siteName
            next.deviceProfileId?.let { p[DEVICE] = it } ?: p.remove(DEVICE)
            next.headsetProfileId?.let { p[HEADSET] = it } ?: p.remove(HEADSET)
            next.ipdMm?.let { p[IPD] = it } ?: p.remove(IPD)
            p[RESEARCH] = next.researchMode
            p[AUTO_LOCK] = next.autoLockSeconds
            p[ONBOARDED] = next.onboardingDone
            p[FIELD_SIGN] = next.fieldCalibratedSign
            next.fieldCalibratedMountDeg?.let { p[FIELD_MOUNT] = it } ?: p.remove(FIELD_MOUNT)
            next.fieldCalibratedAt?.let { p[FIELD_AT] = it } ?: p.remove(FIELD_AT)
            p[RETENTION_AT] = next.lastRetentionCheckAt
            p[TRAINING] = next.trainingModeEnabled
            p[LANG] = next.language
        }
    }

    private companion object {
        val SITE = stringPreferencesKey("site_name")
        val DEVICE = stringPreferencesKey("device_profile_id")
        val HEADSET = stringPreferencesKey("headset_profile_id")
        val IPD = doublePreferencesKey("ipd_mm")
        val RESEARCH = booleanPreferencesKey("research_mode")
        val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val FIELD_SIGN = intPreferencesKey("field_sign")
        val FIELD_MOUNT = doublePreferencesKey("field_mount_deg")
        val FIELD_AT = longPreferencesKey("field_calibrated_at")
        val RETENTION_AT = longPreferencesKey("last_retention_check_at")
        val TRAINING = booleanPreferencesKey("training_mode_enabled")
        val LANG = stringPreferencesKey("language")
    }
}
