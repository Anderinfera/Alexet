package dev.ander.alexet.foldable

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.abs

/** Stable hinge-angle holder — reading [angle] subscribes; the holder identity does not. */
@Stable
class HingeAngleState {
    var angle by mutableStateOf<Float?>(null)
        internal set
}

@Immutable
data class FoldableState(
    val windowSizeClass: WindowSizeClass,
    val foldingFeature: FoldingFeature?,
    val hingeAngleState: HingeAngleState = HingeAngleState(),
) {
    /** Current hinge degrees; reading this from composition subscribes to sensor updates. */
    val hingeAngle: Float?
        get() = hingeAngleState.angle

    val isTabletop: Boolean
        get() = foldingFeature?.state == FoldingFeature.State.HALF_OPENED &&
            foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL

    val isBook: Boolean
        get() = foldingFeature?.state == FoldingFeature.State.HALF_OPENED &&
            foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL

    val isSeparating: Boolean
        get() = foldingFeature?.isSeparating == true

    val hingeBounds: Rect?
        get() = foldingFeature?.bounds

    val isMediumWidth: Boolean
        get() = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val isExpandedWidth: Boolean
        get() = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    val isVerticalSeparating: Boolean
        get() = isSeparating && foldingFeature?.orientation == FoldingFeature.Orientation.VERTICAL

    val showListDetail: Boolean
        get() = !isTabletop && (isMediumWidth || isBook || isVerticalSeparating)

    /**
     * Detects if foldable device is folded/bent along the hinge.
     * Evaluates real-time [hingeAngle] against [thresholdDegrees], with graceful fallback to WindowManager [FoldingFeature].
     */
    fun isBentBook(thresholdDegrees: Float = 20f): Boolean {
        if (isTabletop) return false
        val angle = hingeAngle
        return if (angle != null) {
            val isAngleBent = angle <= (180f - thresholdDegrees) && angle > 5f
            if (foldingFeature != null) {
                isAngleBent && foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL
            } else {
                isAngleBent
            }
        } else {
            isBook || isVerticalSeparating
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberFoldableState(): FoldableState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val layoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, activity) {
        if (activity == null) {
            value = null
            return@produceState
        }
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { value = it }
    }
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val folding = layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull()

    // Stable across hinge ticks — do NOT read .angle while constructing FoldableState,
    // or the whole gallery tree would recompose on every sensor event.
    val hingeAngleState = remember { HingeAngleState() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }
        if (sensorManager != null && sensor != null) {
            val listener = object : SensorEventListener {
                private var lastPublished = Float.NaN
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && event.values.isNotEmpty()) {
                        val next = event.values[0]
                        if (lastPublished.isNaN() || abs(next - lastPublished) >= 0.75f) {
                            lastPublished = next
                            hingeAngleState.angle = next
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            // Only while visible: in the background the hinge events would keep the CPU busy.
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START ->
                        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> sensorManager.unregisterListener(listener)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                sensorManager.unregisterListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    return FoldableState(
        windowSizeClass = adaptiveInfo.windowSizeClass,
        foldingFeature = folding,
        hingeAngleState = hingeAngleState,
    )
}

/** True on phones with a hinge-angle sensor (foldables), also while folded. */
fun Context.hasHingeSensor(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
    return sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
