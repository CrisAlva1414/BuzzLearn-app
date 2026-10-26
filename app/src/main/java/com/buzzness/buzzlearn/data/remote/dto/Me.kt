package com.buzzness.buzzlearn.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Me(
    val id: Long,
    val email: String,
    val nickname: String,
    @SerialName("email_verified") val emailVerified: Boolean,
    @SerialName("daily_goal") val dailyGoal: Int,
    @SerialName("hide_from_ranking") val hideFromRanking: Boolean,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean,
    @SerialName("last_course_id") val lastCourseId: Long? = null,
    val stats: Stats,
) {
    override fun toString(): String =
        "Me(id=$id, email=${redact(email)}, nickname=$nickname, emailVerified=$emailVerified, " +
            "dailyGoal=$dailyGoal, hideFromRanking=$hideFromRanking, " +
            "onboardingCompleted=$onboardingCompleted, lastCourseId=$lastCourseId, stats=$stats)"
}

@Serializable
data class Stats(
    @SerialName("total_xp") val totalXp: Int,
    val level: Int,
    @SerialName("level_xp") val levelXp: Int,
    @SerialName("level_xp_needed") val levelXpNeeded: Int,
    @SerialName("current_streak") val currentStreak: Int,
    @SerialName("longest_streak") val longestStreak: Int,
    @SerialName("streak_freezes") val streakFreezes: Int,
    @SerialName("practiced_today") val practicedToday: Boolean? = null,
    val today: DailyGoalStatus,
)

@Serializable
data class DailyGoalStatus(
    val earned: Int,
    val goal: Int,
    val met: Boolean,
)
