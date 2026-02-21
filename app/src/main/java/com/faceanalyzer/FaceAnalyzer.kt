package com.faceanalyzer

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

class FaceAnalyzer(
    private val context: Context,
    private val onFaceDetected: (FaceAnalysisResult?) -> Unit,
    private val enableMesh: Boolean = false
) : ImageAnalysis.Analyzer {

    private val faceDetector: FaceDetector
    private val meshDetector: com.google.mlkit.vision.facemesh.FaceMeshDetector? = if (enableMesh) {
        FaceMeshDetection.getClient(FaceMeshDetectorOptions.Builder().build())
    } else null

    private val isProcessing = AtomicBoolean(false)

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
        
        faceDetector = FaceDetection.getClient(options)
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    onFaceDetected(null)
                    isProcessing.set(false)
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val face = faces.first()
                processFace(face, imageProxy)
            }
            .addOnFailureListener { e ->
                Log.e("FaceAnalyzer", "Face detection failed", e)
                onFaceDetected(null)
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processFace(face: Face, imageProxy: ImageProxy) {
        val landmarks = extractLandmarks(face)
        
        if (enableMesh && meshDetector != null) {
            val image = InputImage.fromMediaImage(
                imageProxy.image!!,
                imageProxy.imageInfo.rotationDegrees
            )
            meshDetector.process(image)
                .addOnSuccessListener { meshes ->
                    val meshPoints = if (meshes.isNotEmpty()) {
                        extractMeshPoints(meshes.first())
                    } else null
                    
                    val result = analyzeFace(face, landmarks, meshPoints, imageProxy)
                    onFaceDetected(result)
                    isProcessing.set(false)
                    imageProxy.close()
                }
                .addOnFailureListener {
                    val result = analyzeFace(face, landmarks, null, imageProxy)
                    onFaceDetected(result)
                    isProcessing.set(false)
                    imageProxy.close()
                }
        } else {
            val result = analyzeFace(face, landmarks, null, imageProxy)
            onFaceDetected(result)
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    private fun extractLandmarks(face: Face): List<PointF> {
        val points = mutableListOf<PointF>()
        
        val landmarkTypes = listOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.LEFT_CHEEK,
            FaceLandmark.RIGHT_CHEEK,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.LEFT_EAR,
            FaceLandmark.RIGHT_EAR,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM
        )
        
        landmarkTypes.forEach { landmarkType ->
            face.getLandmark(landmarkType)?.let { landmark ->
                points.add(landmark.position)
            }
        }
        
        return points
    }

    private fun extractMeshPoints(mesh: FaceMesh): List<PointF> {
        return mesh.allPoints.map { point ->
            PointF(point.position.x, point.position.y)
        }
    }

    private fun analyzeFace(
        face: Face,
        landmarks: List<PointF>,
        meshPoints: List<PointF>?,
        imageProxy: ImageProxy
    ): FaceAnalysisResult {
        val emotion = detectEmotion(face)
        val faceQuality = analyzeQuality(face)
        val symmetryScore = calculateSymmetry(face)
        val skinCondition = analyzeSkinCondition(face)
        val perfection = calculatePerfection(faceQuality, symmetryScore, skinCondition)
        
        val faceRect = RectF(
            face.boundingBox.left.toFloat(),
            face.boundingBox.top.toFloat(),
            face.boundingBox.right.toFloat(),
            face.boundingBox.bottom.toFloat()
        )

        return FaceAnalysisResult(
            emotion = emotion.first,
            emotionConfidence = emotion.second,
            faceQuality = faceQuality,
            facePerfection = perfection,
            symmetryScore = symmetryScore,
            skinCondition = skinCondition,
            faceRect = faceRect,
            landmarks = landmarks,
            meshPoints = meshPoints
        )
    }

    private fun detectEmotion(face: Face): Pair<Emotion, Float> {
        val smilingProbability = face.smilingProbability ?: 0.5f
        val leftEyeOpenProb = face.leftEyeOpenProbability ?: 0.5f
        val rightEyeOpenProb = face.rightEyeOpenProbability ?: 0.5f
        
        val headEulerAngleX = face.headEulerAngleX
        val headEulerAngleY = face.headEulerAngleY

        return when {
            smilingProbability > 0.7f -> Pair(Emotion.HAPPY, smilingProbability)
            smilingProbability < 0.2f && leftEyeOpenProb < 0.3f -> Pair(Emotion.SAD, 1f - smilingProbability)
            leftEyeOpenProb > 0.9f && rightEyeOpenProb > 0.9f && smilingProbability < 0.3f -> {
                if (kotlin.math.abs(headEulerAngleY) > 15) {
                    Pair(Emotion.ANGRY, 0.7f)
                } else {
                    Pair(Emotion.SURPRISED, 0.7f)
                }
            }
            leftEyeOpenProb < 0.3f && rightEyeOpenProb < 0.3f -> Pair(Emotion.NEUTRAL, 0.6f)
            else -> Pair(Emotion.NEUTRAL, 0.5f)
        }
    }

    private fun analyzeQuality(face: Face): FaceQuality {
        val headEulerAngleX = face.headEulerAngleX
        val headEulerAngleY = face.headEulerAngleY
        val headEulerAngleZ = face.headEulerAngleZ
        
        val poseScore = 1f - (kotlin.math.abs(headEulerAngleX) + 
                              kotlin.math.abs(headEulerAngleY) + 
                              kotlin.math.abs(headEulerAngleZ)) / 90f
        
        val leftEyeOpen = face.leftEyeOpenProbability ?: 0.5f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 0.5f
        val eyeOpenness = (leftEyeOpen + rightEyeOpen) / 2f
        
        val score = (poseScore.coerceIn(0f, 1f) * 0.6f + 
                    eyeOpenness.coerceIn(0.3f, 1f) * 0.4f)
        
        return FaceQuality(
            score = score,
            brightness = 0.7f,
            sharpness = 0.8f,
            poseAngleX = headEulerAngleX,
            poseAngleY = headEulerAngleY,
            poseAngleZ = headEulerAngleZ
        )
    }

    private fun calculateSymmetry(face: Face): Float {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)
        val leftMouth = face.getLandmark(FaceLandmark.MOUTH_LEFT)
        val rightMouth = face.getLandmark(FaceLandmark.MOUTH_RIGHT)
        
        if (leftEye == null || rightEye == null || nose == null) {
            return 0.7f
        }
        
        val eyeDistance = kotlin.math.abs(leftEye.position.x - rightEye.position.x)
        val noseToLeftEye = kotlin.math.abs(nose.position.x - leftEye.position.x)
        val noseToRightEye = kotlin.math.abs(rightEye.position.x - nose.position.x)
        
        val eyeSymmetry = 1f - kotlin.math.abs(noseToLeftEye - noseToRightEye) / eyeDistance
        
        val mouthSymmetry = if (leftMouth != null && rightMouth != null) {
            val mouthCenter = (leftMouth.position.x + rightMouth.position.x) / 2
            1f - kotlin.math.abs(nose.position.x - mouthCenter) / eyeDistance
        } else 0.8f
        
        return ((eyeSymmetry + mouthSymmetry) / 2f).coerceIn(0f, 1f)
    }

    private fun analyzeSkinCondition(face: Face): SkinCondition {
        val baseScore = 0.75f
        
        val smileFactor = face.smilingProbability?.let { 
            0.1f * it 
        } ?: 0.05f
        
        val textureScore = (baseScore + smileFactor).coerceIn(0f, 1f)
        val toneScore = (baseScore + 0.05f).coerceIn(0f, 1f)
        val clarityScore = (baseScore + 0.08f).coerceIn(0f, 1f)
        val overallScore = (textureScore + toneScore + clarityScore) / 3f
        
        return SkinCondition(
            overallScore = overallScore,
            textureScore = textureScore,
            toneScore = toneScore,
            clarityScore = clarityScore
        )
    }

    private fun calculatePerfection(
        quality: FaceQuality,
        symmetry: Float,
        skin: SkinCondition
    ): Float {
        return (quality.score * 0.4f + 
                symmetry * 0.35f + 
                skin.overallScore * 0.25f)
    }

    fun close() {
        faceDetector.close()
        meshDetector?.close()
    }
}
