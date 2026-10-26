package com.mthtamim.phonelink

import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * v1.3.0 CameraX photo capture, no layout file and no preview view:
 * ImageCapture alone bound to this activity's lifecycle is supported
 * by CameraX. The picture lands in Pictures/Lab via MediaStore, the
 * saved Uri goes to var "photo_uri", and the run log reuses the
 * existing lab_photo_saved string from FlowMedia.capture. Every
 * failure path logs and finishes — this activity never hangs and
 * never crashes, so the caller can fall back to the intent path.
 */
class FlowCameraActivity : LocalizedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val facing = intent?.getStringExtra("facing") ?: ""
        val flash = intent?.getStringExtra("flash") ?: ""
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener(
                {
                    try {
                        bindAndCapture(future.get(), facing, flash)
                    } catch (e: Exception) {
                        FlowStore.log(
                            this,
                            "camera bind error: " + (e.message ?: e.javaClass.simpleName)
                        )
                        finish()
                    }
                },
                ContextCompat.getMainExecutor(this)
            )
        } catch (e: Exception) {
            FlowStore.log(this, "camera bind error: " + (e.message ?: e.javaClass.simpleName))
            finish()
        }
    }

    private fun bindAndCapture(
        provider: ProcessCameraProvider,
        facing: String,
        flash: String
    ) {
        try {
            val lens = if (facing.trim().lowercase() == "front") {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(lens)
                .build()
            val flashMode = when (flash.trim().lowercase()) {
                "on" -> ImageCapture.FLASH_MODE_ON
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            val imageCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, imageCapture)
            val values = ContentValues()
            values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "lab_" + System.currentTimeMillis().toString() + ".jpg"
            )
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhoneLink")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = output.savedUri
                            if (savedUri == null) {
                                FlowStore.log(this@FlowCameraActivity, "camera capture error: no saved uri")
                            } else {
                                FlowStore.setVar(
                                    this@FlowCameraActivity,
                                    "photo_uri",
                                    savedUri.toString()
                                )
                                FlowStore.log(
                                    this@FlowCameraActivity,
                                    getString(R.string.flow_photo_saved, savedUri.toString())
                                )
                            }
                        } catch (e: Exception) {
                            FlowStore.log(
                                this@FlowCameraActivity,
                                "camera capture error: " + (e.message ?: e.javaClass.simpleName)
                            )
                        }
                        finish()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        FlowStore.log(
                            this@FlowCameraActivity,
                            "camera capture error: " + (exception.message ?: exception.javaClass.simpleName)
                        )
                        finish()
                    }
                }
            )
        } catch (e: Exception) {
            // Covers a missing requested lens (bind throws) as well as
            // any takePicture setup failure: log and finish, never hang.
            FlowStore.log(this, "camera bind error: " + (e.message ?: e.javaClass.simpleName))
            finish()
        }
    }
}
