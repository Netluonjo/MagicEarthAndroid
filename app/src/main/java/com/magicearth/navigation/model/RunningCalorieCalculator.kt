package com.magicearth.navigation.model

import android.content.Context
import kotlin.math.roundToInt

/**
 * Running Calorie Expenditure & Workout Metrics Calculator.
 *
 * Implements the American College of Sports Medicine (ACSM) metabolic standard:
 * Energy Expenditure (kcal) = Distance (km) * Body Weight (kg) * 1.036
 *
 * Additional sport science adjustments:
 * - Elevation incline gain: +0.0018 kcal per kg per vertical meter climbed.
 * - Pace & duration formatting for running enthusiasts.
 * - Nutrition & hydration equivalent estimations.
 */
object RunningCalorieCalculator {

    private const val PREFS_NAME = "magic_earth_runner_prefs"
    private const val KEY_USER_WEIGHT_KG = "user_weight_kg"
    const val DEFAULT_WEIGHT_KG = 65.0 // Trọng lượng tiêu chuẩn người Việt (65 kg)

    /**
     * Retrieve the runner's configured body weight in kilograms from SharedPreferences.
     */
    fun getUserWeight(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_USER_WEIGHT_KG, DEFAULT_WEIGHT_KG.toFloat()).toDouble()
    }

    /**
     * Save the runner's custom body weight in kilograms to SharedPreferences.
     */
    fun setUserWeight(context: Context, weightKg: Double) {
        val clampedWeight = weightKg.coerceIn(35.0, 180.0)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_USER_WEIGHT_KG, clampedWeight.toFloat()).apply()
    }

    /**
     * Calculate calories burned based on running distance and body weight.
     *
     * @param distanceKm Distance covered in kilometers.
     * @param weightKg Body weight of the runner in kilograms.
     * @param elevationGainMeters Total ascent / elevation gain in meters (optional).
     * @return Total calories burned in kcal (integer rounded).
     */
    fun calculateCalories(
        distanceKm: Double,
        weightKg: Double = DEFAULT_WEIGHT_KG,
        elevationGainMeters: Double = 0.0
    ): Int {
        if (distanceKm <= 0.0) return 0
        // ACSM running formula: 1.036 kcal per kg per km
        val baseCalories = distanceKm * weightKg * 1.036
        // Work against gravity on climbs: ~0.0018 kcal/kg/m
        val climbCalories = if (elevationGainMeters > 0.0) {
            elevationGainMeters * weightKg * 0.0018
        } else {
            0.0
        }
        return (baseCalories + climbCalories).roundToInt()
    }

    /**
     * Format running pace in MM:SS /km (e.g. "05:30 /km").
     */
    fun formatPace(paceMinPerKm: Double): String {
        if (paceMinPerKm <= 0 || paceMinPerKm.isInfinite() || paceMinPerKm.isNaN() || paceMinPerKm > 30.0) {
            return "--:-- /km"
        }
        val totalSeconds = (paceMinPerKm * 60).roundToInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d /km", minutes, seconds)
    }

    /**
     * Format workout duration into HH:MM:SS or MM:SS.
     */
    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds <= 0) return "00:00"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Visual food energy equivalent in Vietnamese cuisine.
     */
    fun getFoodEquivalentVi(calories: Int): String {
        return when {
            calories < 80 -> "~1 quả chuối tiêu chín 🍌"
            calories < 160 -> "~1 cốc nước mía đá mát lạnh 🥤"
            calories < 280 -> "~1 bắp ngô luộc hoặc 1 bánh giò nóng 🌽"
            calories < 450 -> "~1 bát phở bò Hà Nội thơm lừng 🍜"
            calories < 650 -> "~1 suất cơm tấm sườn nướng 🍖"
            calories < 900 -> "~1 cốc trà sữa trân châu full topping 🧋"
            else -> "~1 bữa tiệc BBQ thịnh soạn 🥩🔥"
        }
    }

    /**
     * Recommended hydration replenishment in milliliters (ml).
     * Rule of thumb: ~120 ml per km run.
     */
    fun recommendHydrationMl(distanceKm: Double): Int {
        if (distanceKm <= 0.0) return 0
        return (distanceKm * 120.0).roundToInt()
    }
}
