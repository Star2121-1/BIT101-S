package cn.bit101.android.data.repo.base

import cn.bit101.android.data.school.TeachingAdjustments

/**
 * 教学安排调整（放假 / 调休 / 补课）。
 *
 * 数据源：`jxzx.bit.edu.cn/jxyx/kctz/`（教学运行与考务中心 → 教学运行 → 课程调整），
 * **免登录**。学校每学年发 2~4 条《…教学安排调整的通知》。
 *
 * ⚠️ **只确认了校内可达，外网可达性未验证** ⇒ 实现必须**静默降级**：
 * 取不到就退回缓存（可能过期），再不行返回 null。**绝不抛异常**，因为它挂在课表页上，
 * 一旦抛出去会把整页拖垮 —— 而「拿不到调休」只是少了个增强，不该影响看课表。
 */
interface TeachingAdjustmentRepo {

    /**
     * 取教学安排。
     *
     * @param forceRefresh 跳过缓存直接联网（下拉刷新用）。
     * @return null = 既没网也没缓存（调用方保持原样，不要改课表）。
     */
    suspend fun load(forceRefresh: Boolean = false): TeachingAdjustments?
}
