package com.floorsense.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.floorsense.app.data.model.BuildingProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "floorsense_settings")

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

    private companion object {
        val KEY_BUILDING_PROFILE = stringPreferencesKey("building_profile")
        val KEY_CUSTOM_STANDARD = floatPreferencesKey("custom_standard_height_m")
        val KEY_CUSTOM_B1 = floatPreferencesKey("custom_b1_height_m")
        val KEY_CUSTOM_SUB = floatPreferencesKey("custom_sub_height_m")
        const val CUSTOM = "CUSTOM"
    }
}
