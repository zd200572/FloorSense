package com.floorsense.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 地下停车记忆持久化实体（Room）。
 *
 * 由 [com.floorsense.app.service.ParkingTrackerService] 在结算（SETTLED）或 10 分钟超时时写入，
 * 供 UI 的历史停车列表与“找车”卡片展示。
 */
@Entity(tableName = "parking_records")
data class ParkingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val floorLabel: String,                    // "B1" / "B2" / "B3"
    val floorIndex: Int,                       // 内部索引：-1 / -2 / -3
    val depthMeters: Float,                    // 距地面垂直深度（负值，如 -11.3f）
    val parkedAtMs: Long,                      // 下车（蓝牙断开/手动记录）时刻
    val settledAtMs: Long,                     // 结算时刻
    val confirmedByReturnAscent: Boolean,      // true=已通过回 1F 电梯精确确认；false=仅靠进库下坡预估
    val note: String = ""                      // 用户备注（如“靠近电梯厅立柱”）
)
