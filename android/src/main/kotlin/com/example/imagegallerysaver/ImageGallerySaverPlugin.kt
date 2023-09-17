package com.example.imagegallerysaver

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.annotation.ChecksSdkIntAtLeast
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLConnection


class ImageGallerySaverPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private var applicationContext: Context? = null

    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private val isAndroid10: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q


    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "image_gallery_saver")
        methodChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        val album = call.argument<String?>("album")
        val name = call.argument<String?>("name")

        when (call.method) {
            "saveImageToGallery" -> {
                val image = call.argument<ByteArray?>("imageBytes")
                result.success(
                    saveBytesToGallery(image, name, album)
                        .toHashMap()
                )
            }

            "saveFileToGallery" -> {
                val path = call.argument<String?>("file")
                result.success(
                    saveFileToGallery(path, name, album)
                        .toHashMap()
                )
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = null
        methodChannel.setMethodCallHandler(null)
    }

    private fun generateUri(
        mimeType: String,
        name: String? = null,
        album: String = ""
    ): Uri? {
        val fileName = (name ?: System.currentTimeMillis()
            .toString()) + "." + MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
        val isVideo = mimeType.startsWith("video")

        val albumName = if (album.isNotEmpty()) "/$album" else ""

        return if (isAndroid10) {
            val uri = when {
                isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH, when {
                        isVideo -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES + albumName
                    }
                )
                if (!TextUtils.isEmpty(mimeType)) {
                    put(
                        when {
                            isVideo -> MediaStore.Video.Media.MIME_TYPE
                            else -> MediaStore.Images.Media.MIME_TYPE
                        }, mimeType
                    )
                }
            }
            applicationContext?.contentResolver?.insert(uri, values)

        } else {
            val storePath =
                Environment.getExternalStoragePublicDirectory(
                    when {
                        isVideo -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES + albumName
                    }
                ).absolutePath
            val appDir = File(storePath).apply {
                if (!exists()) {
                    mkdir()
                }
            }

            val file = File(appDir, fileName)
            Uri.fromFile(file)
        }
    }

    /**
     * get file Mime Type
     *
     * @param bytes
     * @return file Mime Type
     */
    private fun getMIMEType(bytes: ByteArray): String {
        val inputStream = ByteArrayInputStream(bytes)
        return inputStream.use {
            URLConnection.guessContentTypeFromStream(it) ?: "application/octet-stream"
        }
    }

    /**
     * Send storage success notification
     *
     * (Olny needed for Android < 10)
     *
     * @param context context
     * @param fileUri file path
     */
    private fun sendBroadcast(context: Context, fileUri: Uri?) {
        if (isAndroid10) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = fileUri
            context.sendBroadcast(mediaScanIntent)
        }
    }

    private fun saveBytesToGallery(
        imageBytes: ByteArray?,
        name: String?,
        album: String?
    ): SaveResultModel {
        // check parameters
        if ((imageBytes == null) || imageBytes.isEmpty()) {
            return SaveResultModel(false, null, "no data provided")
        }

        // check applicationContext
        val context = applicationContext
            ?: return SaveResultModel(false, null, "applicationContext null")

        val mimeType = getMIMEType(imageBytes)

        val fileUri = generateUri(mimeType, name, album ?: "") ?: return SaveResultModel(
            false,
            null,
            "saveImageToGallery fail"
        )

        return try {
            val fos = context.contentResolver.openOutputStream(fileUri)
            if (fos != null) {
                fos.use {
                    it.write(imageBytes)
                    it.flush()
                }
                sendBroadcast(context, fileUri)
                SaveResultModel(
                    fileUri.toString().isNotEmpty(),
                    fileUri.toString(),
                    null
                )
            } else {
                SaveResultModel(false, null, "could not open output stream")
            }
        } catch (e: IOException) {
            SaveResultModel(false, null, e.toString())
        }
    }

    private fun saveFileToGallery(
        filePath: String?,
        name: String?,
        album: String?
    ): SaveResultModel {
        // check parameters
        if (filePath == null) {
            return SaveResultModel(false, null, "parameters error")
        }
        val originalFile = File(filePath)
        if (!originalFile.exists()) {
            return SaveResultModel(false, null, "$filePath does not exist")
        }

        return saveBytesToGallery(
            FileInputStream(originalFile).use { it.readBytes() },
            name,
            album
        )
    }
}

class SaveResultModel(
    private var isSuccess: Boolean,
    private var filePath: String? = null,
    private var errorMessage: String? = null
) {
    fun toHashMap(): HashMap<String, Any?> {
        val hashMap = HashMap<String, Any?>()
        hashMap["isSuccess"] = isSuccess
        hashMap["filePath"] = filePath
        hashMap["errorMessage"] = errorMessage
        return hashMap
    }
}
