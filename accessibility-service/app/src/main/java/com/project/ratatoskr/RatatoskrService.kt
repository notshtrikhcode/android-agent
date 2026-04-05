package com.project.ratatoskr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.protobuf.ByteString
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class RatatoskrService : AccessibilityService() {

    private val nodeCounter = AtomicInteger(0)
    private val backgroundExecutor = Executors.newFixedThreadPool(4)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var grpcServer: Server? = null
    private var streamJob: Job? = null

    companion object {
        private const val TAG = "RATATOSKR"
        private const val GRPC_PORT = 9999
    }

    // ============================================================
    // gRPC Service Implementation
    // ============================================================

    private inner class RatatoskrImpl : RatatoskrGrpc.RatatoskrImplBase() {

        override fun getScreenState(request: ScreenRequest, responseObserver: StreamObserver<ScreenState>) {
            serviceScope.launch {
                try {
                    val builder = ScreenState.newBuilder()
                    
                    // Set timestamp
                    builder.timestamp = System.currentTimeMillis()

                    // Capture screenshot
                    if (request.includeScreenshot) {
                        val screenshot = captureScreenshot()
                        if (screenshot != null) {
                            val stream = ByteArrayOutputStream()
                            val quality = (request.compressionQuality * 100).toInt().coerceIn(0, 100).takeIf { it > 0 } ?: 70
                            screenshot.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                            builder.setScreenshot(ByteString.copyFrom(stream.toByteArray()))
                            builder.setScreenshotFormat("jpeg")
                            builder.screenWidth = screenshot.width
                            builder.screenHeight = screenshot.height
                        } else {
                            // Provide screen size from metrics if screenshot fails
                            val metrics = resources.displayMetrics
                            builder.screenWidth = metrics.widthPixels
                            builder.screenHeight = metrics.heightPixels
                        }
                    }

                    // Traverse nodes
                    val root = rootInActiveWindow
                    if (root != null) {
                        builder.packageName = root.packageName?.toString() ?: "unknown"
                        val nodes = mutableListOf<UiNode>()
                        nodeCounter.set(0) // Reset counter for each request
                        flattenNodes(root, -1, nodes)
                        builder.addAllNodes(nodes)
                    }

                    responseObserver.onNext(builder.build())
                    responseObserver.onCompleted()
                } catch (e: Exception) {
                    Log.e(TAG, "getScreenState failed", e)
                    responseObserver.onError(Status.INTERNAL.withDescription(e.message).asException())
                }
            }
        }

        override fun performAction(request: ActionRequest, responseObserver: StreamObserver<ActionResponse>) {
            val result = ActionResponse.newBuilder()
            try {
                when (request.type) {
                    ActionRequest.ActionType.CLICK -> {
                        val success = clickAt(request.x, request.y)
                        result.setSuccess(success)
                    }
                    ActionRequest.ActionType.TYPE_TEXT -> {
                        val success = false 
                        result.setSuccess(success).setErrorMessage("Type text not fully implemented")
                    }
                    else -> result.setSuccess(false).setErrorMessage("Action type ${request.type} not implemented")
                }
            } catch (e: Exception) {
                result.setSuccess(false).setErrorMessage(e.message ?: "Unknown error")
            }
            responseObserver.onNext(result.build())
            responseObserver.onCompleted()
        }

        override fun streamFrames(request: StreamRequest, responseObserver: StreamObserver<Frame>) {
            val serverObserver = responseObserver as ServerCallStreamObserver<Frame>
            streamJob?.cancel()
            streamJob = serviceScope.launch {
                val delayMs = if (request.fpsLimit > 0) 1000L / request.fpsLimit else 500L
                while (isActive) {
                    if (serverObserver.isCancelled) break
                    
                    val screenshot = captureScreenshot()
                    if (screenshot != null) {
                        val stream = ByteArrayOutputStream()
                        val quality = (request.compressionQuality * 100).toInt().coerceIn(0, 100).takeIf { it > 0 } ?: 50
                        screenshot.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                        
                        val frame = Frame.newBuilder()
                            .setData(ByteString.copyFrom(stream.toByteArray()))
                            .setTimestamp(System.currentTimeMillis())
                            .setWidth(screenshot.width)
                            .setHeight(screenshot.height)
                            .build()
                        
                        responseObserver.onNext(frame)
                    }
                    delay(delayMs)
                }
            }
        }
    }

    private fun flattenNodes(node: AccessibilityNodeInfo, parentId: Int, list: MutableList<UiNode>) {
        val id = nodeCounter.incrementAndGet()
        val builder = UiNode.newBuilder()
            .setId(id)
            .setParentId(parentId)
            .setClassName(node.className?.toString() ?: "")
            .setText(node.text?.toString() ?: "")
            .setContentDescription(node.contentDescription?.toString() ?: "")
            .setResourceId(node.viewIdResourceName ?: "")
            .setIsClickable(node.isClickable)
            .setIsVisible(node.isVisibleToUser)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        builder.setBounds(com.project.ratatoskr.Rect.newBuilder()
            .setLeft(rect.left)
            .setTop(rect.top)
            .setRight(rect.right)
            .setBottom(rect.bottom)
            .build())

        list.add(builder.build())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                flattenNodes(child, id, list)
            }
        }
    }

    private fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        continuation.resume(bitmap?.copy(Bitmap.Config.ARGB_8888, false))
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception", e)
                continuation.resume(null)
            }
        } else {
            continuation.resume(null)
        }
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onServiceConnected() {
        // Instead of replacing the serviceInfo, we modify the existing one 
        // to preserve capabilities defined in XML (like canTakeScreenshot)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or 
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        serviceInfo = info

        try {
            grpcServer = NettyServerBuilder.forPort(GRPC_PORT)
                .addService(RatatoskrImpl())
                .executor(backgroundExecutor)
                .build()
            backgroundExecutor.execute { 
                try {
                    grpcServer?.start()
                    Log.d(TAG, "gRPC Server started on $GRPC_PORT")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start gRPC server", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server builder failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        streamJob?.cancel()
        grpcServer?.shutdown()
        try {
            grpcServer?.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            grpcServer?.shutdownNow()
        }
        super.onDestroy()
    }
}
