package com.faceanalyzer

import android.graphics.PointF
import android.util.SizeF

data class FaceAnalysisResult(
    val emotion: Emotion,
    val emotionConfidence: Float,
    val faceQuality: FaceQuality,
    val facePerfection: Float,
    val symmetryScore: Float,
    val skinCondition: SkinCondition,
    val faceRect: RectF,
    val landmarks: List<PointF>? = null,
    val meshPoints: List<PointF>? = null
)

data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

enum class Emotion(val emoji: String, val displayName: String, val color: Long) {
    HAPPY("😊", "Happy", 0xFF22C55E),
    SAD("😢", "Sad", 0xFF6366F1),
    ANGRY("😠", "Angry", 0xFFEF4444),
    SURPRISED("😲", "Surprised", 0xFFF59E0B),
    NEUTRAL("😐", "Neutral", 0xFF94A3B8),
    FEARFUL("😨", "Fearful", 0xFF8B5CF6),
    DISGUSTED("🤢", "Disgusted", 0xFF14B8A6),
    CONTEMPT("😏", "Contempt", 0xFFEC4899)
}

data class FaceQuality(
    val score: Float,
    val brightness: Float,
    val sharpness: Float,
    val poseAngleX: Float,
    val poseAngleY: Float,
    val poseAngleZ: Float
) {
    val qualityLevel: QualityLevel
        get() = when {
            score >= 0.8f -> QualityLevel.EXCELLENT
            score >= 0.6f -> QualityLevel.GOOD
            score >= 0.4f -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
}

enum class QualityLevel(val displayName: String, val color: Long) {
    EXCELLENT("Excellent", 0xFF22C55E),
    GOOD("Good", 0xFF14B8A6),
    FAIR("Fair", 0xFFF59E0B),
    POOR("Poor", 0xFFEF4444)
}

data class SkinCondition(
    val overallScore: Float,
    val textureScore: Float,
    val toneScore: Float,
    val clarityScore: Float
) {
    val condition: String
        get() = when {
            overallScore >= 0.8f -> "Excellent"
            overallScore >= 0.6f -> "Good"
            overallScore >= 0.4f -> "Fair"
            else -> "Needs Attention"
        }
}
