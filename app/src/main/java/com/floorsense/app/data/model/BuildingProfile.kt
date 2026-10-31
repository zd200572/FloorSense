package com.floorsense.app.data.model

/**
 * 建筑层高配置模板。
 *
 * 地下车库采用非对称层高模型（见规格书 §2.3）：
 * 1F -> B1 因覆土层 / 管线夹层 / 挑高大堂，落差显著大于标准层高；
 * B1 往下（B2/B3/B4）按地库标准层高计算。
 */
data class BuildingProfile(
    val name: String,
    val standardFloorHeightM: Float,         // 地上标准层高 (住宅 2.9m, 办公楼 4.0m, 商场 5.0m)
    val basementFirstHeightM: Float = 4.2f,  // 1F 到 B1 的垂直落差
    val basementSubHeightM: Float = 3.3f     // B1 到 B2/B3 的单层落差
) {
    companion object {
        val RESIDENTIAL = BuildingProfile("住宅模式", 2.9f, 3.8f, 3.0f)
        val OFFICE = BuildingProfile("写字楼模式", 4.0f, 4.5f, 3.4f)
        val MALL = BuildingProfile("商场/综合体", 5.0f, 5.0f, 3.6f)

        val ALL = listOf(RESIDENTIAL, OFFICE, MALL)
    }
}
