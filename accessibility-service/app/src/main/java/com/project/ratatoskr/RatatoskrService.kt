package com.project.ratatoskr

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.protobuf.ByteString
import io.grpc.Server
import io.grpc.Status
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class RatatoskrService : AccessibilityService() {

    private val backgroundExecutor = Executors.newFixedThreadPool(4)

    private val serviceScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob()
    )

    private val eventFlow = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var grpcServer: Server? = null
    private var streamJob: Job? = null

    private val lastEventTime = AtomicLong(System.currentTimeMillis())
    private val screenGeneration = AtomicLong(0)

    private val lastSnapshotNodes = ConcurrentHashMap<String, UiNode>()

    @Volatile
    private var lastScreenHash: String = ""

    @Volatile
    private var currentActivityName: String = "unknown"

    companion object {
        private const val TAG = "RATATOSKR_RUNTIME"
        private const val GRPC_PORT = 9999

        private const val STABILITY_QUIET_PERIOD_MS = 400L
        private const val STABILITY_TIMEOUT_MS = 2500L

        private const val ACTION_RETRY_COUNT = 2
    }

    // ============================================================
    // gRPC
    // ============================================================

    private inner class RatatoskrImpl : RatatoskrGrpc.RatatoskrImplBase() {

        override fun getScreenState(
            request: ScreenRequest,
            responseObserver: StreamObserver<ScreenState>
        ) {
            serviceScope.launch {
                try {
                    val stable = waitUntilUiIdle()

                    val builder = ScreenState.newBuilder()
                        .setTimestamp(System.currentTimeMillis())
                        .setActivityName(currentActivityName)
                        .setIsStable(stable)
                        .setScreenGeneration(screenGeneration.get())

                    rootInActiveWindow?.packageName?.toString()?.let {
                        builder.packageName = it
                    }

                    val windowsList = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                    ) {
                        windows.filter {
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                        }
                    } else {
                        emptyList()
                    }

                    builder.addAllWindows(
                        windowsList.map { transformWindow(it) }
                    )

                    val currentNodes = mutableListOf<UiNode>()

                    windowsList.forEach { window ->
                        val root = window.root ?: return@forEach

                        try {
                            flattenNodes(
                                node = root,
                                parentStableId = "root",
                                list = currentNodes,
                                visibleOnly = request.includeVisibleOnly,
                                windowId = window.id,
                                siblingIndex = 0
                            )
                        } finally {
                            root.recycle()
                        }
                    }

                    val currentHash = computeFingerprint(currentNodes)

                    builder.screenHash = currentHash

                    if (
                        request.lastScreenHash.isNotEmpty() &&
                        request.lastScreenHash == lastScreenHash
                    ) {
                        builder.isIncremental = true

                        val (added, removed) = calculateDiff(currentNodes)

                        builder.addAllNodes(added)
                        builder.addAllRemovedNodeIds(removed)
                    } else {
                        builder.isIncremental = false
                        builder.addAllNodes(currentNodes)
                    }

                    lastSnapshotNodes.clear()
                    currentNodes.forEach {
                        lastSnapshotNodes[it.stableId] = it
                    }

                    lastScreenHash = currentHash

                    if (request.includeScreenshot) {
                        captureScreenshot()?.let { bitmap ->
                            val stream = ByteArrayOutputStream()

                            val quality = (
                                    request.compressionQuality * 100
                                    ).toInt().coerceIn(10, 100)

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                quality,
                                stream
                            )

                            builder.screenshot = ByteString.copyFrom(
                                stream.toByteArray()
                            )

                            builder.screenshotFormat = "jpeg"
                            builder.screenWidth = bitmap.width
                            builder.screenHeight = bitmap.height

                            bitmap.recycle()
                        }
                    }

                    responseObserver.onNext(builder.build())
                    responseObserver.onCompleted()

                } catch (e: Exception) {
                    Log.e(TAG, "getScreenState failed", e)

                    responseObserver.onError(
                        Status.INTERNAL
                            .withDescription(e.message)
                            .asException()
                    )
                }
            }
        }

        override fun performAction(
            request: ActionRequest,
            responseObserver: StreamObserver<ActionResponse>
        ) {
            serviceScope.launch {

                val result = ActionResponse.newBuilder()

                try {
                    var success = false

                    repeat(ACTION_RETRY_COUNT) {

                        success = when (request.type) {

                            ActionRequest.ActionType.CLICK -> {
                                if (request.nodeStableId.isNotEmpty()) {
                                    executeSmartClick(request.nodeStableId)
                                } else {
                                    performGestureWithResult(
                                        request.x,
                                        request.y,
                                        request.x,
                                        request.y,
                                        50
                                    )
                                }
                            }

                            ActionRequest.ActionType.LONG_CLICK -> {
                                performGestureWithResult(
                                    request.x,
                                    request.y,
                                    request.x,
                                    request.y,
                                    800
                                )
                            }

                            ActionRequest.ActionType.SWIPE -> {
                                performGestureWithResult(
                                    request.x,
                                    request.y,
                                    request.endX,
                                    request.endY,
                                    request.durationMs.coerceAtLeast(50)
                                )
                            }

                            ActionRequest.ActionType.TYPE_TEXT -> {
                                executeSmartType(
                                    request.nodeStableId,
                                    request.text
                                )
                            }

                            ActionRequest.ActionType.SCROLL -> {
                                executeScroll(
                                    request.nodeStableId,
                                    request.text
                                )
                            }

                            ActionRequest.ActionType.KEY_EVENT -> {
                                handleKeyEvent(request.keyCode)
                            }

                            ActionRequest.ActionType.WAIT_FOR_STABLE -> {
                                waitUntilUiIdle()
                            }

                            else -> false
                        }

                        if (success) return@repeat

                        delay(300)
                    }

                    if (success && request.waitForStabilization) {
                        waitUntilUiIdle()
                    }

                    result.success = success
                    result.resultScreenHash = lastScreenHash
                    result.waitAfterMs = 150

                } catch (e: Exception) {
                    Log.e(TAG, "performAction failed", e)

                    result.success = false
                    result.errorMessage = e.message ?: "unknown error"
                }

                responseObserver.onNext(result.build())
                responseObserver.onCompleted()
            }
        }

        override fun streamFrames(
            request: StreamRequest,
            responseObserver: StreamObserver<Frame>
        ) {
            val observer = responseObserver as ServerCallStreamObserver<Frame>

            streamJob?.cancel()

            streamJob = serviceScope.launch {

                val delayMs = if (request.fpsLimit > 0) {
                    (1000L / request.fpsLimit).coerceAtLeast(80)
                } else {
                    250L
                }

                while (isActive && !observer.isCancelled) {

                    captureScreenshot()?.let { bitmap ->

                        try {
                            val stream = ByteArrayOutputStream()

                            val quality = (
                                    request.compressionQuality * 100
                                    ).toInt().coerceIn(10, 100)

                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                quality,
                                stream
                            )

                            val frame = Frame.newBuilder()
                                .setData(ByteString.copyFrom(stream.toByteArray()))
                                .setTimestamp(System.currentTimeMillis())
                                .setWidth(bitmap.width)
                                .setHeight(bitmap.height)
                                .build()

                            responseObserver.onNext(frame)

                        } finally {
                            bitmap.recycle()
                        }
                    }

                    delay(delayMs)
                }
            }
        }

        override fun streamEvents(
            request: EventRequest,
            responseObserver: StreamObserver<UiEvent>
        ) {
            val observer = responseObserver as ServerCallStreamObserver<UiEvent>

            val job = serviceScope.launch {
                eventFlow.collect { event ->
                    if (!observer.isCancelled) {
                        responseObserver.onNext(event)
                    }
                }
            }

            observer.setOnCancelHandler {
                job.cancel()
            }
        }
    }

    // ============================================================
    // TREE
    // ============================================================

    private fun flattenNodes(
        node: AccessibilityNodeInfo,
        parentStableId: String,
        list: MutableList<UiNode>,
        visibleOnly: Boolean,
        windowId: Int,
        siblingIndex: Int
    ) {

        if (visibleOnly && !node.isVisibleToUser) {
            return
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 1 || rect.height() <= 1) {
            return
        }

        val stableId = generateStableId(
            node,
            parentStableId,
            siblingIndex
        )

        val builder = UiNode.newBuilder()
            .setStableId(stableId)
            .setParentStableId(parentStableId)
            .setWindowId(windowId)
            .setClassName(node.className?.toString() ?: "")
            .setText(advancedNormalizeText(node.text?.toString()))
            .setContentDescription(
                node.contentDescription?.toString() ?: ""
            )
            .setResourceId(node.viewIdResourceName ?: "")
            .setIsClickable(node.isClickable)
            .setIsVisible(node.isVisibleToUser)
            .setIsScrollable(node.isScrollable)
            .setIsFocused(node.isFocused)
            .setIsEditable(node.isEditable)
            .setIsPassword(node.isPassword)
            .setIsSelected(node.isSelected)
            .setIsChecked(node.isChecked)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.hint = node.hintText?.toString() ?: ""
        }

        builder.bounds = com.project.ratatoskr.Rect.newBuilder()
            .setLeft(rect.left)
            .setTop(rect.top)
            .setRight(rect.right)
            .setBottom(rect.bottom)
            .build()

        list.add(builder.build())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            try {
                flattenNodes(
                    child,
                    stableId,
                    list,
                    visibleOnly,
                    windowId,
                    i
                )
            } finally {
                child.recycle()
            }
        }
    }

    private fun generateStableId(
        node: AccessibilityNodeInfo,
        parentId: String,
        siblingIndex: Int
    ): String {

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val raw = buildString {
            append(node.className)
            append('|')
            append(node.viewIdResourceName ?: "")
            append('|')
            append(node.childCount)
            append('|')
            append(node.isClickable)
            append('|')
            append(rect.width() / 20)
            append('|')
            append(rect.height() / 20)
            append('|')
            append(rect.top / 40)
            append('|')
            append(siblingIndex)
            append('|')
            append(parentId)
        }

        return md5(raw)
    }

    private fun calculateDiff(
        current: List<UiNode>
    ): Pair<List<UiNode>, List<String>> {

        val added = current.filter {
            !lastSnapshotNodes.containsKey(it.stableId) ||
                    lastSnapshotNodes[it.stableId] != it
        }

        val currentIds = current.map { it.stableId }.toSet()

        val removed = lastSnapshotNodes.keys.filter {
            it !in currentIds
        }

        return Pair(added, removed)
    }

    private fun computeFingerprint(nodes: List<UiNode>): String {

        val structural = nodes.joinToString(",") {
            "${it.className}:${it.resourceId}"
        }

        val semantic = nodes
            .filter { it.text.isNotEmpty() }
            .joinToString("|") { it.text }

        return md5(structural + semantic)
    }

    private fun computeLightweightFingerprint(): String {

        val builder = StringBuilder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            windows
                .filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                .forEach { window ->

                    val root = window.root ?: return@forEach

                    try {
                        val rect = Rect()
                        root.getBoundsInScreen(rect)

                        builder.append(root.packageName)
                        builder.append(root.className)
                        builder.append(root.childCount)
                        builder.append(rect.width())
                        builder.append(rect.height())
                    } finally {
                        root.recycle()
                    }
                }
        }

        return md5(builder.toString())
    }

    private suspend fun waitUntilUiIdle(): Boolean {

        val start = System.currentTimeMillis()

        var lastHash = ""
        var stableTicks = 0

        while (
            System.currentTimeMillis() - start < STABILITY_TIMEOUT_MS
        ) {

            val now = System.currentTimeMillis()

            if (now - lastEventTime.get() >= STABILITY_QUIET_PERIOD_MS) {

                val hash = computeLightweightFingerprint()

                if (hash == lastHash && hash.isNotEmpty()) {
                    stableTicks++

                    if (stableTicks >= 2) {
                        return true
                    }
                } else {
                    stableTicks = 0
                    lastHash = hash
                }
            }

            delay(120)
        }

        return false
    }

    private fun transformWindow(
        window: AccessibilityWindowInfo
    ): WindowInfo {

        val builder = WindowInfo.newBuilder()
            .setId(window.id)
            .setType(window.type)
            .setLayer(window.layer)
            .setIsFocused(window.isFocused)

        val rect = Rect()
        window.getBoundsInScreen(rect)

        builder.bounds = com.project.ratatoskr.Rect.newBuilder()
            .setLeft(rect.left)
            .setTop(rect.top)
            .setRight(rect.right)
            .setBottom(rect.bottom)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.title = window.title?.toString() ?: ""
        }

        return builder.build()
    }

    // ============================================================
    // ACTIONS
    // ============================================================

    private fun findNodeByStableId(
        stableId: String
    ): AccessibilityNodeInfo? {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            windows
                .filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
                .forEach { window ->

                    val root = window.root ?: return@forEach

                    try {
                        val result = findInTree(
                            root,
                            "root",
                            stableId,
                            0
                        )

                        if (result != null) {
                            return result
                        }
                    } finally {
                        root.recycle()
                    }
                }
        }

        return null
    }

    private fun findInTree(
        node: AccessibilityNodeInfo,
        parentId: String,
        targetId: String,
        siblingIndex: Int
    ): AccessibilityNodeInfo? {

        val currentId = generateStableId(
            node,
            parentId,
            siblingIndex
        )

        if (currentId == targetId) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {

            val child = node.getChild(i) ?: continue

            try {
                val result = findInTree(
                    child,
                    currentId,
                    targetId,
                    i
                )

                if (result != null) {
                    return result
                }
            } finally {
                child.recycle()
            }
        }

        return null
    }

    private fun findClickableAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        var current: AccessibilityNodeInfo? = node

        while (current != null) {

            if (current.isClickable && current.isVisibleToUser) {
                return current
            }

            val parent = current.parent

            if (current != node) {
                current.recycle()
            }

            current = parent
        }

        return null
    }

    private fun findScrollableAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        var current: AccessibilityNodeInfo? = node

        while (current != null) {

            if (current.isScrollable && current.isVisibleToUser) {
                return current
            }

            val parent = current.parent

            if (current != node) {
                current.recycle()
            }

            current = parent
        }

        return null
    }

    private suspend fun executeSmartClick(
        stableId: String
    ): Boolean {

        val originalNode = findNodeByStableId(stableId)
            ?: return false

        val clickableNode =
            findClickableAncestor(originalNode)
                ?: originalNode

        return try {

            if (
                clickableNode.isClickable &&
                clickableNode.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            ) {
                true
            } else {

                val rect = Rect()
                clickableNode.getBoundsInScreen(rect)

                performGestureWithResult(
                    rect.centerX(),
                    rect.centerY(),
                    rect.centerX(),
                    rect.centerY(),
                    50
                )
            }

        } finally {
            if (clickableNode != originalNode) {
                try {
                    clickableNode.recycle()
                } catch (_: Exception) {
                }
            }

            try {
                originalNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun executeSmartType(
        stableId: String,
        text: String
    ): Boolean {

        val node = if (stableId.isNotEmpty()) {
            findNodeByStableId(stableId)
        } else {
            rootInActiveWindow
        } ?: return false

        return try {

            if (!node.isEditable) {
                return false
            }

            node.performAction(
                AccessibilityNodeInfo.ACTION_FOCUS
            )

            val args = Bundle()

            args.putCharSequence(
                AccessibilityNodeInfo
                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )

            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args
            )

        } finally {
            if (node != rootInActiveWindow) {
                try {
                    node.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun executeScroll(
        stableId: String,
        direction: String
    ): Boolean {

        val originalNode = if (stableId.isNotEmpty()) {
            findNodeByStableId(stableId)
        } else {
            rootInActiveWindow
        } ?: return false

        val scrollableNode =
            findScrollableAncestor(originalNode)
                ?: originalNode

        return try {

            if (!scrollableNode.isScrollable) {
                return false
            }

            when (direction.lowercase()) {

                "forward", "down" -> {
                    scrollableNode.performAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    )
                }

                "backward", "up" -> {
                    scrollableNode.performAction(
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                }

                else -> false
            }

        } finally {
            if (scrollableNode != originalNode) {
                try {
                    scrollableNode.recycle()
                } catch (_: Exception) {
                }
            }

            if (originalNode != rootInActiveWindow) {
                try {
                    originalNode.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun performGestureWithResult(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Int
    ): Boolean = suspendCancellableCoroutine { continuation ->

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    duration.toLong().coerceAtLeast(10L)
                )
            )
            .build()

        val callback = object : GestureResultCallback() {

            override fun onCompleted(
                gestureDescription: GestureDescription?
            ) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }

            override fun onCancelled(
                gestureDescription: GestureDescription?
            ) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

        if (!dispatchGesture(gesture, callback, null)) {
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
    }

    private fun handleKeyEvent(
        keyCode: ActionRequest.KeyCode
    ): Boolean {

        val action = when (keyCode) {
            ActionRequest.KeyCode.BACK -> GLOBAL_ACTION_BACK
            ActionRequest.KeyCode.HOME -> GLOBAL_ACTION_HOME
            ActionRequest.KeyCode.RECENTS -> GLOBAL_ACTION_RECENTS
            else -> return false
        }

        return performGlobalAction(action)
    }

    // ============================================================
    // UTIL
    // ============================================================

    private fun advancedNormalizeText(
        text: String?
    ): String {

        if (text == null) {
            return ""
        }

        var result = Normalizer.normalize(
            text,
            Normalizer.Form.NFKC
        )

        result = result.replace(
            Regex("[™®©\\p{C}\\p{P}]"),
            ""
        )

        result = result.replace(
            Regex("\\s+"),
            " "
        )

        return result.trim().lowercase()
    }

    private fun md5(input: String): String {
        return MessageDigest
            .getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") {
                "%02x".format(it)
            }
    }

    private suspend fun captureScreenshot(): Bitmap? =
        suspendCancellableCoroutine { continuation ->

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {

                        override fun onSuccess(
                            screenshot: ScreenshotResult
                        ) {

                            try {
                                val hardwareBuffer: HardwareBuffer =
                                    screenshot.hardwareBuffer

                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    hardwareBuffer,
                                    screenshot.colorSpace
                                )

                                val safeBitmap = bitmap?.copy(
                                    Bitmap.Config.ARGB_8888,
                                    false
                                )

                                bitmap?.recycle()
                                hardwareBuffer.close()

                                continuation.resume(safeBitmap)

                            } catch (e: Exception) {
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                continuation.resume(null)
            }
        }

    // ============================================================
    // ACCESSIBILITY
    // ============================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        if (event == null) {
            return
        }

        lastEventTime.set(System.currentTimeMillis())
        screenGeneration.incrementAndGet()

        if (
            event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            currentActivityName =
                event.className?.toString() ?: "unknown"
        }

        serviceScope.launch {

            val type = when (event.eventType) {

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    UiEvent.EventType.WINDOW_STATE_CHANGED
                }

                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    UiEvent.EventType.SCROLLED
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    UiEvent.EventType.TEXT_CHANGED
                }

                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    UiEvent.EventType.NOTIFICATION
                }

                else -> {
                    UiEvent.EventType.SCREEN_CHANGED
                }
            }

            val uiEvent = UiEvent.newBuilder()
                .setType(type)
                .setTimestamp(System.currentTimeMillis())
                .setPackageName(event.packageName?.toString() ?: "")
                .setClassName(event.className?.toString() ?: "")
                .setScreenHash(lastScreenHash)
                .setScreenGeneration(screenGeneration.get())
                .build()

            eventFlow.emit(uiEvent)
        }
    }

    override fun onServiceConnected() {

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {

            eventTypes = AccessibilityEvent.TYPES_ALL_MASK

            feedbackType =
                AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            notificationTimeout = 100
        }

        try {
            grpcServer = NettyServerBuilder
                .forPort(GRPC_PORT)
                .addService(RatatoskrImpl())
                .executor(backgroundExecutor)
                .build()

            backgroundExecutor.execute {
                grpcServer?.start()
            }

            Log.i(TAG, "gRPC started on :$GRPC_PORT")

        } catch (e: Exception) {
            Log.e(TAG, "gRPC start failed", e)
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {

        streamJob?.cancel()

        grpcServer?.shutdownNow()

        backgroundExecutor.shutdownNow()

        super.onDestroy()
    }
}
