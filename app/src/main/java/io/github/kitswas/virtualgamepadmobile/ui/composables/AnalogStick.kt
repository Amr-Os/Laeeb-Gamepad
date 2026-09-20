package io.github.kitswas.virtualgamepadmobile.ui.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.kitswas.VGP_Data_Exchange.GameButtons
import io.github.kitswas.VGP_Data_Exchange.GamepadReading
import io.github.kitswas.virtualgamepadmobile.ui.utils.HapticUtils
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class AnalogStickType {
    LEFT, RIGHT
}

private class AnalogStickUiState {
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var visualX by mutableFloatStateOf(0f)
    var visualY by mutableFloatStateOf(0f)
    var isPressed by mutableStateOf(false)
    var hasExitedDeadzone by mutableStateOf(false)
}

private fun updateThumbstickValues(
    type: AnalogStickType,
    gamepadState: GamepadReading,
    x: Float,
    y: Float,
) {
    when (type) {
        AnalogStickType.LEFT -> {
            gamepadState.LeftThumbstickX = x
            gamepadState.LeftThumbstickY = y
        }

        AnalogStickType.RIGHT -> {
            gamepadState.RightThumbstickX = x
            gamepadState.RightThumbstickY = y
        }
    }
}

private fun thumbstickButtonMask(type: AnalogStickType): Int = when (type) {
    AnalogStickType.LEFT -> GameButtons.LeftThumbstick.value
    AnalogStickType.RIGHT -> GameButtons.RightThumbstick.value
}

private fun resetAnalogStick(
    state: AnalogStickUiState,
    type: AnalogStickType,
    gamepadState: GamepadReading,
) {
    state.offsetX = 0f
    state.offsetY = 0f
    state.visualX = 0f
    state.visualY = 0f
    state.hasExitedDeadzone = false
    updateThumbstickValues(type, gamepadState, 0f, 0f)
}

private fun handleAnalogDrag(
    state: AnalogStickUiState,
    dragX: Float,
    dragY: Float,
    maxOffset: Float,
    type: AnalogStickType,
    gamepadState: GamepadReading,
    view: android.view.View,
) {
    state.offsetX += dragX
    state.offsetY += dragY

    val magnitude = sqrt(state.offsetX * state.offsetX + state.offsetY * state.offsetY)
    val normalizedDistance = (magnitude / maxOffset).coerceIn(0f, 1f)
    
    // Minimalist Haptics: Only trigger once when crossing the 15% deadzone threshold
    if (normalizedDistance > 0.15f && !state.hasExitedDeadzone) {
        HapticUtils.performButtonPressFeedback(view)
        state.hasExitedDeadzone = true
    } else if (normalizedDistance <= 0.15f) {
        state.hasExitedDeadzone = false
    }

    if (magnitude <= 0f) {
        return
    }

    val scaleFactor = if (magnitude > maxOffset) maxOffset / magnitude else 1f
    state.visualX = state.offsetX * scaleFactor
    state.visualY = state.offsetY * scaleFactor
    updateThumbstickValues(type, gamepadState, state.visualX / maxOffset, state.visualY / maxOffset)
}

private fun toggleThumbstickButton(
    state: AnalogStickUiState,
    type: AnalogStickType,
    gamepadState: GamepadReading,
    view: android.view.View,
) {
    val mask = thumbstickButtonMask(type)
    if (!state.isPressed) {
        state.isPressed = true
        gamepadState.ButtonsDown = gamepadState.ButtonsDown or mask
        HapticUtils.performButtonPressFeedback(view)
        return
    }

    state.isPressed = false
    gamepadState.ButtonsDown = gamepadState.ButtonsDown and mask.inv()
    gamepadState.ButtonsUp = gamepadState.ButtonsUp or mask
    HapticUtils.performGestureEndFeedback(view)
}

@Composable
fun AnalogStick(
    modifier: Modifier = Modifier,
    // Repurposed your existing parameters to match the new 2D HUD style without breaking API
    ringColor: Color = Color.White.copy(alpha = 0.15f), 
    ringWidth: Dp = 2.dp,
    outerCircleColor: Color = Color.Transparent, 
    outerCircleWidth: Dp = 4.dp, // Still used for sizing calculations
    innerCircleColor: Color = Color.White, // Flat, high-contrast inner thumbstick
    innerCircleRadius: Dp = 32.dp,
    gamepadState: GamepadReading,
    type: AnalogStickType,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val state = remember { AnalogStickUiState() }

    // Calculate maximum offset once
    val maxOffset = with(density) {
        (innerCircleRadius + outerCircleWidth).toPx()
    }

    // 2D Animation States
    val isActive = state.isPressed || state.hasExitedDeadzone
    val stickScale by animateFloatAsState(targetValue = if (isActive) 0.85f else 1f, label = "stickScale")
    val stickAlpha by animateFloatAsState(targetValue = if (isActive) 1f else 0.6f, label = "stickAlpha")

    Box(
        modifier = modifier.testTag("AnalogStick_${type.name}"),
        contentAlignment = Alignment.Center
    ) {
        // Flat, HUD-style outer boundary ring (replaces nested filled circles)
        Box(
            modifier = Modifier
                .size((innerCircleRadius + outerCircleWidth + ringWidth) * 2)
                .border(width = ringWidth, color = ringColor, shape = CircleShape)
                .background(outerCircleColor, CircleShape)
        )

        // Flat, vibrant inner thumbstick
        Box(
            modifier = Modifier
                .testTag("AnalogStick_${type.name}_Handle")
                .size(innerCircleRadius * 2)
                .offset {
                    IntOffset(
                        state.visualX.roundToInt(),
                        state.visualY.roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = stickScale
                    scaleY = stickScale
                    alpha = stickAlpha
                }
                .background(color = innerCircleColor, shape = CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { _ ->
                            HapticUtils.performGestureStartFeedback(view)
                        },
                        onDragEnd = {
                            resetAnalogStick(state, type, gamepadState)
                            HapticUtils.performGestureEndFeedback(view)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            handleAnalogDrag(
                                state = state,
                                dragX = dragAmount.x,
                                dragY = dragAmount.y,
                                maxOffset = maxOffset,
                                type = type,
                                gamepadState = gamepadState,
                                view = view,
                            )
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { _ ->
                            toggleThumbstickButton(state, type, gamepadState, view)
                        }
                    )
                }
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun AnalogStickPreview() {
    AnalogStick(
        gamepadState = GamepadReading(),
        type = AnalogStickType.LEFT,
    )
}
