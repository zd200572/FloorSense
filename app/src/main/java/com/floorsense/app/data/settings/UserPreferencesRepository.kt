package com.floorsense.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.floorsense.app.data.model.BuildingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "floorsense_settings")

/**
 * 楼层锚点与停车追踪的持久化快照（应用退出/被杀前的记忆）。
 */
data class AnchorSnapshot(
    val mode: String,                        // 仅 HARDWARE 模式的锚点值得恢复（模拟器锚点是虚构的）
    val basePressure1F: Float,               // 1F 基准气压
    val lockedAltitudeM: Float,              // 锁定高度
    val parkingPhase: String? = null,        // 可恢复的停车阶段（PARKED_WAITING_ASCENT）
    val carParkedPressureHpa: Float? = null,
    val parkedTimestampMs: Long? = null,
    val estimatedFloorLabel: String? = null,
    val depthMeters: Float? = null,
    val confirmedByReturnAscent: Boolean = false,
    val savedAtMs: Long
)

/**
 * 用户偏好（DataStore）：建筑层高模板选择，含用户自填层高的「自定义」模板。
 */
class UserPreferencesRepository(private val context: Context) {

    val buildingProfile: Flow<BuildingProfile> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_BUILDING_PROFILE]) {
            BuildingProfile.RESIDENTIAL.name -> BuildingProfile.RESIDENTIAL
            BuildingProfile.MALL.name -> BuildingProfile.MALL
            CUSTOM -> BuildingProfile(
                name = "自定义",
                standardFloorHeightM = prefs[KEY_CUSTOM_STANDARD]
                    ?: BuildingProfile.OFFICE.standardFloorHeightM,
                basementFirstHeightM = prefs[KEY_CUSTOM_B1]
                    ?: BuildingProfile.OFFICE.basementFirstHeightM,
                basementSubHeightM = prefs[KEY_CUSTOM_SUB]
                    ?: BuildingProfile.OFFICE.basementSubHeightM
            )
            else -> BuildingProfile.OFFICE
        }
    }

    suspend fun setBuildingProfile(profile: BuildingProfile) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUILDING_PROFILE] = profile.name
        }
    }

    /** 保存用户自填层高并切换到「自定义」模板 */
    suspend fun setCustomProfile(standardM: Float, b1M: Float, subM: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_STANDARD] = standardM
            prefs[KEY_CUSTOM_B1] = b1M
            prefs[KEY_CUSTOM_SUB] = subM
            prefs[KEY_BUILDING_PROFILE] = CUSTOM
        }
    }

    /** 持久化锚点快照（低频调用：状态变化/每 10 秒/退出时） */
    suspend fun saveAnchor(snapshot: AnchorSnapshot) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANCHOR_MODE] = snapshot.mode
            prefs[KEY_ANCHOR_BASE_P] = snapshot.basePressure1F
            prefs[KEY_ANCHOR_ALT] = snapshot.lockedAltitudeM
            prefs[KEY_ANCHOR_PARKING_PHASE] = snapshot.parkingPhase ?: ""
            snapshot.carParkedPressureHpa?.let { prefs[KEY_ANCHOR_CAR_P] = it }
            snapshot.parkedTimestampMs?.let { prefs[KEY_ANCHOR_PARKED_AT] = it }
            prefs[KEY_ANCHOR_EST_LABEL] = snapshot.estimatedFloorLabel ?: ""
            prefs[KEY_ANCHOR_DEPTH] = snapshot.depthMeters ?: 0f
            // Preferences 无 Boolean 类型，用 "1"/"0" 存储
            prefs[KEY_ANCHOR_CONFIRMED] = if (snapshot.confirmedByReturnAscent) "1" else "0"
            prefs[KEY_ANCHOR_SAVED_AT] = snapshot.savedAtMs
        }
    }

    suspend fun loadAnchor(): AnchorSnapshot? {
        val prefs = context.dataStore.data.first()
        if (!prefs.contains(KEY_ANCHOR_SAVED_AT)) return null
        return AnchorSnapshot(
            mode = prefs[KEY_ANCHOR_MODE] ?: return null,
            basePressure1F = prefs[KEY_ANCHOR_BASE_P] ?: return null,
            lockedAltitudeM = prefs[KEY_ANCHOR_ALT] ?: 0f,
            parkingPhase = prefs[KEY_ANCHOR_PARKING_PHASE]?.takeIf { it.isNotEmpty() },
            carParkedPressureHpa = prefs[KEY_ANCHOR_CAR_P],
            parkedTimestampMs = prefs[KEY_ANCHOR_PARKED_AT],
            estimatedFloorLabel = prefs[KEY_ANCHOR_EST_LABEL]?.takeIf { it.isNotEmpty() },
            depthMeters = prefs[KEY_ANCHOR_DEPTH],
            confirmedByReturnAscent = prefs[KEY_ANCHOR_CONFIRMED] == "1",
            savedAtMs = prefs[KEY_ANCHOR_SAVED_AT] ?: return null
        )
    }

    /** 手动切换数据源等场景下清除旧锚点，避免恢复过期状态 */
    suspend fun clearAnchor() {
        context.dataStore.edit { prefs ->
            listOf(
                KEY_ANCHOR_MODE, KEY_ANCHOR_BASE_P, KEY_ANCHOR_ALT, KEY_ANCHOR_PARKING_PHASE,
                KEY_ANCHOR_CAR_P, KEY_ANCHOR_PARKED_AT, KEY_ANCHOR_EST_LABEL,
                KEY_ANCHOR_DEPTH, KEY_ANCHOR_CONFIRMED, KEY_ANCHOR_SAVED_AT
            ).forEach { prefs.remove(it) }
        }
    }

    private companion object {
        val KEY_BUILDING_PROFILE = stringPreferencesKey("building_profile")
        val KEY_CUSTOM_STANDARD = floatPreferencesKey("custom_standard_height_m")
        val KEY_CUSTOM_B1 = floatPreferencesKey("custom_b1_height_m")
        val KEY_CUSTOM_SUB = floatPreferencesKey("custom_sub_height_m")
        const val CUSTOM = "CUSTOM"

        val KEY_ANCHOR_MODE = stringPreferencesKey("anchor_mode")
        val KEY_ANCHOR_BASE_P = floatPreferencesKey("anchor_base_pressure_hpa")
        val KEY_ANCHOR_ALT = floatPreferencesKey("anchor_locked_altitude_m")
        val KEY_ANCHOR_PARKING_PHASE = stringPreferencesKey("anchor_parking_phase")
        val KEY_ANCHOR_CAR_P = floatPreferencesKey("anchor_car_parked_pressure_hpa")
        val KEY_ANCHOR_PARKED_AT = longPreferencesKey("anchor_parked_at_ms")
        val KEY_ANCHOR_EST_LABEL = stringPreferencesKey("anchor_estimated_floor_label")
        val KEY_ANCHOR_DEPTH = floatPreferencesKey("anchor_depth_meters")
        val KEY_ANCHOR_CONFIRMED = stringPreferencesKey("anchor_parking_confirmed")
        val KEY_ANCHOR_SAVED_AT = longPreferencesKey("anchor_saved_at_ms")
    }
}
