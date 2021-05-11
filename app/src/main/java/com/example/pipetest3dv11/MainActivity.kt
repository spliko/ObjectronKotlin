package com.example.pipetest3dv11

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.components.CameraHelper.CameraFacing
import com.google.mediapipe.components.CameraXPreviewHelper
import com.google.mediapipe.components.ExternalTextureConverter
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.PermissionHelper
import com.google.mediapipe.framework.AndroidAssetUtil
import com.google.mediapipe.framework.AndroidPacketCreator
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.glutil.EglManager


@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private val FLIP_FRAMES_VERTICALLY = true

    private val OBJ_TEXTURE = "sneaker/texture.jpg"
    private val OBJ_FILE = "sneaker/model.obj.uuu"
    private val BOX_TEXTURE = "classic_colors.png"
    private val BOX_FILE = "box.obj.uuu"

    private val BINARY_GRAPH_NAME = "mobile_gpu_binary_graph.binarypb"
    private val INPUT_VIDEO_STREAM_NAME = "input_video"
    private val OUTPUT_VIDEO_STREAM_NAME = "output_video"
    //private val OUTPUT_LANDMARKS_STREAM_NAME = "mask_model_matrices"

    private val CAMERA_FACING = CameraFacing.BACK

    companion object {
        init {
            // Load all native libraries needed by the app.
            System.loadLibrary("mediapipe_jni")
            System.loadLibrary("opencv_java3")

        }
    }

    // Assets.
    private var objTexture: Bitmap? = null
    private var boxTexture: Bitmap? = null

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private var previewFrameTexture: SurfaceTexture? = null
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private var previewDisplayView: SurfaceView? = null
    // Creates and manages an {@link EGLContext}.
    private var eglManager: EglManager? = null
    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private var processor: FrameProcessor? = null
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private var converter: ExternalTextureConverter? = null
    // Handles camera access via the {@link CameraX} Jetpack support library.
    private var cameraHelper: CameraXPreviewHelper? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize asset manager so that MediaPipe native libraries can access the app assets,
        // e.g., binary graphs.
        //AndroidAssetUtil.initializeNativeAssetManager(this)
        setContentView(R.layout.activity_main)
        previewDisplayView = SurfaceView(this)
        setupPreviewDisplayView()
        // ApplicationInfo for retrieving metadata defined in the manifest.


        eglManager = EglManager(null)
        processor = FrameProcessor(
                this,
                eglManager!!.nativeContext,
                BINARY_GRAPH_NAME,
                INPUT_VIDEO_STREAM_NAME,
                OUTPUT_VIDEO_STREAM_NAME
        )
        processor!!.videoSurfaceOutput.setFlipY(FLIP_FRAMES_VERTICALLY)


        var applicationInfo: ApplicationInfo? = null
        try {
            applicationInfo = packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.GET_META_DATA
            )
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Cannot find application info: $e")
        }

        // Get allowed object category.
        val categoryName = applicationInfo!!.metaData.getString("categoryName")
        // Get maximum allowed number of objects.
        val maxNumObjects = applicationInfo.metaData.getInt("maxNumObjects")
        val modelScale = parseFloatArrayFromString(
                applicationInfo.metaData.getString("modelScale")
        )
        val modelTransform = parseFloatArrayFromString(
                applicationInfo.metaData.getString("modelTransformation")
        )
        prepareDemoAssets()
        val packetCreator: AndroidPacketCreator = processor!!.packetCreator
        val inputSidePackets: MutableMap<String, Packet> = HashMap()
        inputSidePackets["obj_asset_name"] = packetCreator.createString(OBJ_FILE)
        inputSidePackets["box_asset_name"] = packetCreator.createString(BOX_FILE)
        inputSidePackets["obj_texture"] = packetCreator.createRgbaImageFrame(objTexture)
        inputSidePackets["box_texture"] = packetCreator.createRgbaImageFrame(boxTexture)
        inputSidePackets["allowed_labels"] = packetCreator.createString(categoryName)
        inputSidePackets["max_num_objects"] = packetCreator.createInt32(maxNumObjects)
        inputSidePackets["model_scale"] = packetCreator.createFloat32Array(modelScale)
        inputSidePackets["model_transformation"] = packetCreator.createFloat32Array(modelTransform)
        processor!!.setInputSidePackets(inputSidePackets)
        PermissionHelper.checkAndRequestCameraPermissions(this)
    }

    override fun onResume() {
        super.onResume()
        converter = ExternalTextureConverter(eglManager!!.context)
        converter!!.setFlipY(FLIP_FRAMES_VERTICALLY)
        converter!!.setConsumer(processor)
        if (PermissionHelper.cameraPermissionsGranted(this)) {
            startCamera()
        }
    }

    private fun startCamera() {
        cameraHelper = CameraXPreviewHelper()
        cameraHelper!!.setOnCameraStartedListener { surfaceTexture: SurfaceTexture? ->
            previewFrameTexture = surfaceTexture
            // Make the display view visible to start showing the preview. This triggers the
            // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
            previewDisplayView!!.visibility = View.VISIBLE
        }
        cameraHelper!!.startCamera(this, CAMERA_FACING,  /*surfaceTexture=*/null)
    }

    override fun onPause() {
        super.onPause()
        converter!!.close()
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun setupPreviewDisplayView() {
        previewDisplayView!!.visibility = View.GONE
        val viewGroup = findViewById<ViewGroup>(R.id.preview_display_layout)
        viewGroup.addView(previewDisplayView)
        previewDisplayView!!
                .holder
                .addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                processor!!.videoSurfaceOutput.setSurface(holder.surface)
                            }

                            override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                            ) {
                                val viewSize = Size(width, height)
                                val displaySize =
                                        cameraHelper!!.computeDisplaySizeFromViewSize(viewSize)
                                converter!!.setSurfaceTextureAndAttachToGLContext(
                                        previewFrameTexture, displaySize.width, displaySize.height
                                )
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                processor!!.videoSurfaceOutput.setSurface(null)
                            }
                        })
    }

    private fun prepareDemoAssets() {
        AndroidAssetUtil.initializeNativeAssetManager(this)
        // We render from raw data with openGL, so disable decoding preprocessing
        val decodeOptions = BitmapFactory.Options()
        decodeOptions.inScaled = false
        decodeOptions.inDither = false
        decodeOptions.inPremultiplied = false
        try {
            val inputStream = assets.open(OBJ_TEXTURE)
            objTexture = BitmapFactory.decodeStream(inputStream, null /*outPadding*/, decodeOptions)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing object texture; error: $e")
            throw IllegalStateException(e)
        }
        try {
            val inputStream = assets.open(BOX_TEXTURE)
            boxTexture = BitmapFactory.decodeStream(inputStream, null /*outPadding*/, decodeOptions)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing box texture; error: $e")
            throw RuntimeException(e)
        }
    }

    private fun parseFloatArrayFromString(string: String?): FloatArray {
        val elements = string!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val array = FloatArray(elements.size)
        for (i in elements.indices) {
            array[i] = elements[i].toFloat()
        }
        return array
    }
}