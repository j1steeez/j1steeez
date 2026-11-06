package com.j1steeez.usbvision

import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen UVC preview. Volume keys / tap grab a frame, POST to Gemini, show the answer.
 */
class MainActivity : CameraActivity() {

    private val grabController = GrabController()
    private val geminiClient by lazy { GeminiClient(BuildConfig.GEMINI_KEY) }
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var rootLayout: FrameLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var textureView: AspectRatioTextureView

    private val resultText = mutableStateOf<String?>(null)
    private val inFlight = mutableStateOf(false)
    private val cameraReady = mutableStateOf(false)
    private val deviceLines = mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        ensureCameraPermission()
        refreshUsbDeviceList()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun getRootView(layoutInflater: LayoutInflater): View {
        rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        cameraContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        textureView = AspectRatioTextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }

        val composeView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OverlayUi(
                    result = resultText.value,
                    loading = inFlight.value,
                    cameraReady = cameraReady.value,
                    devices = deviceLines.value,
                    onRetry = { retryUsb() },
                    onCapture = { triggerCapture() }
                )
            }
        }

        // Compose needs owners when hosted outside setContent / ComponentActivity path.
        rootLayout.setViewTreeLifecycleOwner(this)
        rootLayout.setViewTreeViewModelStoreOwner(this)
        rootLayout.setViewTreeSavedStateRegistryOwner(this)

        rootLayout.addView(cameraContainer)
        rootLayout.addView(composeView)
        return rootLayout
    }

    override fun getCameraView(): IAspectRatio = textureView

    override fun getCameraViewContainer(): ViewGroup = cameraContainer

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest {
        // libausbc 3.3.3 defaults UVC preview to MJPEG; request 1280x720 (or nearest).
        return CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
    }

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                cameraReady.value = true
            }
            ICameraStateCallBack.State.CLOSED,
            ICameraStateCallBack.State.ERROR -> {
                cameraReady.value = false
                refreshUsbDeviceList()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolume) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                triggerCapture()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun triggerCapture() {
        if (inFlight.value) return
        if (!cameraReady.value) return

        val jpeg = grabController.grabJpegBase64(textureView)
        if (jpeg.isNullOrEmpty()) {
            resultText.value = "ERR: frame grab failed"
            return
        }

        resultText.value = null
        inFlight.value = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    geminiClient.analyze(jpeg)
                } catch (e: Exception) {
                    "ERR: ${e.message ?: "network"}"
                }
            }
            resultText.value = text
            inFlight.value = false
        }
    }

    private fun retryUsb() {
        refreshUsbDeviceList()
        // Re-register camera client so permission / attach flow runs again.
        try {
            unRegisterMultiCamera()
        } catch (_: Exception) {
        }
        try {
            registerMultiCamera()
        } catch (_: Exception) {
        }
        val devices = getDeviceList().orEmpty()
        val first = devices.firstOrNull()
        if (first != null) {
            requestPermission(first)
        }
    }

    private fun refreshUsbDeviceList() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val lines = usbManager.deviceList.values.map { deviceLabel(it) }
        deviceLines.value = lines
        val uvc = getDeviceList().orEmpty()
        if (uvc.isEmpty() && !cameraReady.value) {
            // Keep setup visible until a camera opens.
        }
    }

    private fun deviceLabel(device: UsbDevice): String {
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "USB device"
        return "$name  vid=${device.vendorId} pid=${device.productId}"
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
private fun OverlayUi(
    result: String?,
    loading: Boolean,
    cameraReady: Boolean,
    devices: List<String>,
    onRetry: () -> Unit,
    onCapture: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (result != null || !cameraReady) ComposeColor.Black
                else ComposeColor.Transparent
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = cameraReady && !loading,
                onClick = onCapture
            )
    ) {
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter),
                color = ComposeColor.White,
                trackColor = ComposeColor.Transparent
            )
        }

        if (!cameraReady && result == null && !loading) {
            SetupScreen(devices = devices, onRetry = onRetry)
        }

        if (result != null && !loading) {
            val isError = result.startsWith("ERR")
            Text(
                text = result,
                color = if (isError) ComposeColor(0xFFFF6B6B) else ComposeColor.White,
                fontSize = if (isError) 48.sp else 88.sp,
                lineHeight = if (isError) 56.sp else 96.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SetupScreen(devices: List<String>, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No UVC camera",
            color = ComposeColor.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (devices.isEmpty()) {
                "No USB devices enumerated. Plug in a UVC camera via OTG and tap Retry."
            } else {
                "USB devices:\n" + devices.joinToString("\n")
            },
            color = ComposeColor(0xFFCCCCCC),
            fontSize = 16.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = ComposeColor.White,
                contentColor = ComposeColor.Black
            )
        ) {
            Text("Retry")
        }
    }
}
