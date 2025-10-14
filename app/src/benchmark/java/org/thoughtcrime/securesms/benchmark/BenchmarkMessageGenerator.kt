package org.thoughtcrime.securesms.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import kotlin.random.Random

/**
 * Generates test messages of various types and sizes for benchmarking
 */
object BenchmarkMessageGenerator {
  private const val TAG = "BenchmarkMessageGenerator"
  
  fun generateTextMessage(index: Int, size: MessageSize): String {
    val length = size.getTextLength()
    val prefix = "Benchmark message #$index: "
    val remaining = length - prefix.length
    
    if (remaining <= 0) {
      return prefix
    }
    
    val content = generateRandomText(remaining)
    return prefix + content
  }
  
  private fun generateRandomText(length: Int): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 "
    return (1..length)
      .map { chars[Random.nextInt(chars.length)] }
      .joinToString("")
  }
  
  /**
   * Gets a voice message file from assets or generates a placeholder
   */
  fun getVoiceMessageFile(context: Context, size: MessageSize): File? {
    val assetName = when (size) {
      MessageSize.SMALL -> "voice_10s.m4a"
      MessageSize.MEDIUM -> "voice_30s.m4a"
      MessageSize.LARGE -> "voice_60s.m4a"
    }
    
    return try {
      // Try to load from benchmark assets
      val assetFile = loadAssetFile(context, assetName, "benchmark")
      if (assetFile != null) {
        return assetFile
      }
      
      // If asset doesn't exist, generate a dummy audio file
      Log.w(TAG, "Asset $assetName not found, generating dummy file")
      generateDummyAudioFile(context, size)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get voice message file", e)
      null
    }
  }
  
  /**
   * Gets an image file from assets or generates one
   */
  fun getImageFile(context: Context, size: MessageSize): File? {
    val assetName = when (size) {
      MessageSize.SMALL -> "image_100kb.jpg"
      MessageSize.MEDIUM -> "image_500kb.jpg"
      MessageSize.LARGE -> "image_2mb.jpg"
    }
    
    return try {
      val assetFile = loadAssetFile(context, assetName, "benchmark")
      if (assetFile != null) {
        return assetFile
      }
      
      // Generate if not found
      Log.w(TAG, "Asset $assetName not found, generating image")
      generateImageFile(context, size)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get image file", e)
      null
    }
  }
  
  /**
   * Generates an image file of approximately the target size
   */
  fun generateImageFile(context: Context, size: MessageSize): File {
    val targetSizeKB = size.getImageSizeKB()
    val cacheDir = File(context.cacheDir, "benchmark_images").apply { mkdirs() }
    val imageFile = File(cacheDir, "benchmark_image_${targetSizeKB}kb.jpg")
    
    // Check if already generated
    if (imageFile.exists() && imageFile.length() > 0) {
      val actualSizeKB = imageFile.length() / 1024
      if (actualSizeKB >= targetSizeKB * 0.8 && actualSizeKB <= targetSizeKB * 1.2) {
        Log.d(TAG, "Using cached image: ${imageFile.name}")
        return imageFile
      }
    }
    
    // Generate new image
    val width = 1920
    val height = 1080
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    // Fill with random pattern to prevent excessive compression
    val paint = Paint()
    val random = Random(42) // Use fixed seed for reproducibility
    
    // Draw background
    canvas.drawColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
    
    // Draw random rectangles to add content
    val rectCount = 100 + (targetSizeKB / 10)
    for (i in 0 until rectCount) {
      paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
      val left = random.nextInt(width).toFloat()
      val top = random.nextInt(height).toFloat()
      val right = (left + random.nextInt(200)).coerceAtMost(width.toFloat())
      val bottom = (top + random.nextInt(200)).coerceAtMost(height.toFloat())
      canvas.drawRect(left, top, right, bottom, paint)
    }
    
    // Save with adjusted quality to reach target size
    var quality = 85
    var attempt = 0
    
    while (attempt < 10) {
      FileOutputStream(imageFile).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      }
      
      val actualSizeKB = imageFile.length() / 1024
      Log.d(TAG, "Generated image: ${actualSizeKB}KB (target: ${targetSizeKB}KB, quality: $quality)")
      
      // Check if within acceptable range (±20%)
      if (actualSizeKB >= targetSizeKB * 0.8 && actualSizeKB <= targetSizeKB * 1.2) {
        break
      }
      
      // Adjust quality
      quality = if (actualSizeKB < targetSizeKB) {
        (quality + 5).coerceAtMost(100)
      } else {
        (quality - 5).coerceAtLeast(10)
      }
      
      attempt++
    }
    
    bitmap.recycle()
    Log.i(TAG, "Image generated: ${imageFile.name}, size: ${imageFile.length() / 1024}KB")
    return imageFile
  }
  
  /**
   * Gets a video file from assets or generates a placeholder
   */
  fun getVideoFile(context: Context, size: MessageSize): File? {
    val assetName = when (size) {
      MessageSize.SMALL -> "video_5mb.mp4"
      MessageSize.MEDIUM -> "video_20mb.mp4"
      MessageSize.LARGE -> "video_50mb.mp4"
    }
    
    return try {
      val assetFile = loadAssetFile(context, assetName, "benchmark")
      if (assetFile != null) {
        return assetFile
      }
      
      // Generate dummy video file
      Log.w(TAG, "Asset $assetName not found, generating dummy file")
      generateDummyVideoFile(context, size)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get video file", e)
      null
    }
  }
  
  /**
   * Generates a test file of specified size
   */
  fun generateTestFile(context: Context, size: MessageSize, messageType: MessageType): File {
    val sizeBytes = when (messageType) {
      MessageType.FILE -> size.getFileSizeKB() * 1024
      MessageType.LARGE_FILE -> size.getLargeFileSizeMB() * 1024L * 1024
      else -> size.getFileSizeKB() * 1024
    }
    
    val cacheDir = File(context.cacheDir, "benchmark_files").apply { mkdirs() }
    val fileName = "benchmark_file_${sizeBytes / 1024}kb.bin"
    val file = File(cacheDir, fileName)
    
    // Check if already generated
    if (file.exists() && file.length() == sizeBytes) {
      Log.d(TAG, "Using cached file: ${file.name}")
      return file
    }
    
    // Generate file with random data
    val random = SecureRandom()
    val buffer = ByteArray(8192)
    var written = 0L
    
    FileOutputStream(file).use { out ->
      while (written < sizeBytes) {
        val toWrite = minOf(buffer.size.toLong(), sizeBytes - written).toInt()
        random.nextBytes(buffer)
        out.write(buffer, 0, toWrite)
        written += toWrite
      }
    }
    
    Log.i(TAG, "File generated: ${file.name}, size: ${file.length() / 1024}KB")
    return file
  }
  
  private fun loadAssetFile(context: Context, assetName: String, subdir: String): File? {
    return try {
      val assetPath = if (subdir.isNotEmpty()) "$subdir/$assetName" else assetName
      val inputStream = context.assets.open(assetPath)
      
      val cacheDir = File(context.cacheDir, "benchmark_assets").apply { mkdirs() }
      val outputFile = File(cacheDir, assetName)
      
      if (!outputFile.exists()) {
        FileOutputStream(outputFile).use { output ->
          inputStream.copyTo(output)
        }
      }
      
      inputStream.close()
      Log.d(TAG, "Loaded asset: $assetName")
      outputFile
    } catch (e: IOException) {
      null
    }
  }
  
  private fun generateDummyAudioFile(context: Context, size: MessageSize): File {
    val targetSizeKB = size.getVoiceDurationSeconds() * 16 // Rough estimate: 16KB per second
    return generateBinaryFile(context, "benchmark_audio_${size.name.lowercase()}.m4a", targetSizeKB * 1024L)
  }
  
  private fun generateDummyVideoFile(context: Context, size: MessageSize): File {
    val targetSizeMB = size.getVideoSizeMB()
    return generateBinaryFile(context, "benchmark_video_${size.name.lowercase()}.mp4", targetSizeMB * 1024L * 1024)
  }
  
  private fun generateBinaryFile(context: Context, fileName: String, sizeBytes: Long): File {
    val cacheDir = File(context.cacheDir, "benchmark_files").apply { mkdirs() }
    val file = File(cacheDir, fileName)
    
    if (file.exists() && file.length() == sizeBytes) {
      return file
    }
    
    val random = SecureRandom()
    val buffer = ByteArray(8192)
    var written = 0L
    
    FileOutputStream(file).use { out ->
      while (written < sizeBytes) {
        val toWrite = minOf(buffer.size.toLong(), sizeBytes - written).toInt()
        random.nextBytes(buffer)
        out.write(buffer, 0, toWrite)
        written += toWrite
      }
    }
    
    return file
  }
}

