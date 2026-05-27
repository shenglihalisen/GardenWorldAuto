package com.gardenworld.auto.automation

/**
 * 自动化配置
 */
data class AutomationConfig(
    /** 截图间隔（毫秒），默认60秒 */
    val captureInterval: Long = 60_000L,
    /** 捕获间隔（秒），用于UI显示 */
    val captureIntervalSeconds: Int = 60,
    /** 错误重试延迟（毫秒） */
    val errorRetryDelay: Long = 10_000L,
    /** 自动收获 */
    val autoHarvest: Boolean = true,
    /** 自动浇水 */
    val autoWater: Boolean = true,
    /** 自动施肥 */
    val autoFertilize: Boolean = true,
    /** 自动种植 */
    val autoPlant: Boolean = true,
    /** 自动领取任务 */
    val autoCollectTasks: Boolean = true,
    /** 自动购买种子 */
    val autoBuySeeds: Boolean = false,
    /** 最低金币数才购物 */
    val minGoldForShopping: Int = 5000
) {
    companion object {
        /** 默认60秒截图间隔 */
        const val DEFAULT_INTERVAL_SECONDS = 60
        
        /** 最小间隔10秒 */
        const val MIN_INTERVAL_SECONDS = 10
        
        /** 最大间隔300秒（5分钟） */
        const val MAX_INTERVAL_SECONDS = 300
    }
}
