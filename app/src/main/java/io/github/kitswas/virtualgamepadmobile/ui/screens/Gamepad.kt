package io.github.kitswas.virtualgamepadmobile.ui.screens
import android.content.pm.ActivityInfo
import io.github.kitswas.virtualgamepadmobile.ui.utils.LockScreenOrientation
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices.DESKTOP
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kitswas.VGP_Data_Exchange.GamepadReading
import io.github.kitswas.virtualgamepadmobile.R
import io.github.kitswas.virtualgamepadmobile.data.CustomProfileStorage
import io.github.kitswas.virtualgamepadmobile.data.GyroAssign
import io.github.kitswas.virtualgamepadmobile.data.PreviewBase
import io.github.kitswas.virtualgamepadmobile.data.PreviewHeightDp
import io.github.kitswas.virtualgamepadmobile.data.PreviewWidthDp
import io.github.kitswas.virtualgamepadmobile.data.SettingsRepository
import io.github.kitswas.virtualgamepadmobile.data.StickResponseMode
import io.github.kitswas.virtualgamepadmobile.data.defaultButtonConfigs
import io.github.kitswas.virtualgamepadmobile.data.defaultPollingDelay
import io.github.kitswas.virtualgamepadmobile.data.steerDeflectionFromAzimuth
import io.github.kitswas.virtualgamepadmobile.data.steerDeflectionFromPitch
import io.github.kitswas.virtualgamepadmobile.network.ConnectionViewModel
import io.github.kitswas.virtualgamepadmobile.ui.composables.DrawGamepad
import io.github.kitswas.virtualgamepadmobile.ui.utils.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val tag = "GamePadScreen"

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun GamePad(
    connectionViewModel: ConnectionViewModel?,
    onNavigateBack: () -> Unit,
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
    val gamepadState by rememberSaveable { mutableStateOf(GamepadReading()) }
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val pollingDelay =
        settingsRepository.pollingDelay.collectAsState(defaultPollingDelay).value.toLong()
    val buttonConfigs =
        settingsRepository.buttonConfigs.collectAsState(defaultButtonConfigs).value
    val stickResponseMode by settingsRepository.stickResponseMode.collectAsState(
        initial = StickResponseMode.default
    )
    val activeProfileId by settingsRepository.activeProfileId.collectAsState(initial = "")
    val profileStorage = remember { CustomProfileStorage(context) }

    // Which stick(s) the tilt sensors drive, derived from the per-stick gyro flags.
    val gyroAssign = GyroAssign.fromConfigs(buttonConfigs)

    // Latest gyro-driven stick values (normalized -1..1), used to move the knob visually
    var gyroValue by remember { mutableStateOf(0f to 0f) }
    // Latest horizontal heading (yaw, radians) from the rotation sensor, used for recentering.
    var latestAzimuth by remember { mutableFloatStateOf(0f) }
    // Latest vertical tilt (pitch, radians) from the rotation sensor, used for recentering.
    var latestPitch by remember { mutableFloatStateOf(0f) }
    // Calibration references: angles captured when the stick(s) were centered.
    var yawRef by remember { mutableFloatStateOf(0f) }
    var pitchRef by remember { mutableFloatStateOf(0f) }
    var gyroEnabled by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current

    val screenHeight = configuration.screenHeightDp
    val screenWidth = configuration.screenWidthDp

    val isStopping = remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // Rotation sensor: drives the assigned sticks when any gyro flag is set. The
    // calibration references are captured on the first reading after the sensor is
    // registered, so the neutral/stick-center position matches however the phone is
    // being held at that moment (e.g. in a gamepad grip) instead of absolute level.
    // Only rotation is used: the azimuth (yaw) drives X (left/right, like holding a
    // steering wheel) and the pitch drives Y (top/bottom edge nodding toward/away).
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    LaunchedEffect(gyroAssign) {
        if (gyroAssign == GyroAssign.OFF) {
            gyroEnabled = false
            gyroValue = 0f to 0f
            return@LaunchedEffect
        }
        // Prefer the gyro-fused rotation sensor (no magnetometer jitter indoors) and fall
        // back to the full rotation vector, which also fuses the magnetometer for an
        // absolute heading that is immune to drift.
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            Toast.makeText(context, "Gyro sensor not available", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }

        var calibrated = false

        fun publish(x: Float, y: Float) {
            gyroValue = x to y
            if (gyroAssign == GyroAssign.LEFT || gyroAssign == GyroAssign.BOTH) {
                gamepadState.LeftThumbstickX = x
                gamepadState.LeftThumbstickY = y
            }
            if (gyroAssign == GyroAssign.RIGHT || gyroAssign == GyroAssign.BOTH) {
                gamepadState.RightThumbstickX = x
                gamepadState.RightThumbstickY = y
            }
        }

        val rotationListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotation = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                // orientation[0] = azimuth (yaw about the world vertical axis)
                // orientation[1] = pitch (top/bottom edge tilt)
                latestAzimuth = orientation[0]
                latestPitch = orientation[1]
                if (!calibrated && !gyroEnabled) {
                    yawRef = orientation[0]
                    pitchRef = orientation[1]
                    calibrated = true
                    gyroEnabled = true
                }
                val steerX = steerDeflectionFromAzimuth(orientation[0], yawRef)
                val steerY = steerDeflectionFromPitch(orientation[1], pitchRef)
                publish(steerX, steerY)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        try {
            sensorManager.registerListener(rotationListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            awaitCancellation()
        } finally {
            sensorManager.unregisterListener(rotationListener)
            gyroEnabled = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DrawGamepad(
            screenWidth,
            screenHeight,
            gamepadState,
            buttonConfigs,
            stickResponseMode,
            gyroValue.first,
            gyroValue.second,
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GamepadPill(
                text = "Joystick: ${stickResponseMode.displayName}",
                onClick = {
                    val next = StickResponseMode.entries[(stickResponseMode.ordinal + 1) % StickResponseMode.entries.size]
                    scope.launch { settingsRepository.setStickResponseMode(next) }
                    Toast.makeText(context, "Joystick: ${next.displayName}", Toast.LENGTH_SHORT).show()
                }
            )
            GamepadPill(
                text = "Gyro: ${gyroAssign.displayName}",
                onClick = {
                    val next = GyroAssign.entries[(gyroAssign.ordinal + 1) % GyroAssign.entries.size]
                    val updatedConfigs = next.applyTo(buttonConfigs)
                    scope.launch {
                        settingsRepository.setAllButtonConfigs(updatedConfigs)
                        val exists = activeProfileId.isNotEmpty() &&
                            profileStorage.loadProfileConfigs(activeProfileId) != null
                        if (exists) {
                            val profileName = profileStorage.listProfiles()
                                .find { it.id == activeProfileId }?.name ?: "Custom Layout"
                            profileStorage.saveProfile(activeProfileId, profileName, updatedConfigs)
                        }
                    }
                    Toast.makeText(context, "Gyro: ${next.displayName}", Toast.LENGTH_SHORT).show()
                }
            )
            if (gyroAssign != GyroAssign.OFF) {
                GamepadPill(
                    text = "Recenter Gyro",
                    onClick = {
                        yawRef = latestAzimuth
                        pitchRef = latestPitch
                        Toast.makeText(context, "Gyro recentered", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    val activity = LocalContext.current.findActivity()
    androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        .addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY
                && activity?.isChangingConfigurations != true
            ) {
                if (connectionViewModel != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            isStopping.value = true
                            gamepadState.ButtonsUp = gamepadState.ButtonsDown
                            gamepadState.ButtonsDown = 0
                            gamepadState.LeftThumbstickX = 0F
                            gamepadState.LeftThumbstickY = 0F
                            gamepadState.RightThumbstickX = 0F
                            gamepadState.RightThumbstickY = 0F
                            gamepadState.LeftTrigger = 0F
                            gamepadState.RightTrigger = 0F
                            connectionViewModel.enqueueGamepadState(gamepadState)
                        } catch (e: Exception) {
                            Log.d(tag, "Error while cleaning up gamepad state: ${e.message}")
                        }
                    }
                }
            }
        })

    val connectionState = connectionViewModel?.uiState?.collectAsState(initial = null)?.value

    val connectionLostMessage = connectionState?.takeIf { !it.connected }?.let { state ->
        state.error?.let {
            stringResource(R.string.gamepad_connection_lost_error, it)
        } ?: stringResource(R.string.gamepad_connection_lost)
    }

    LaunchedEffect(connectionLostMessage) {
        if (connectionLostMessage != null) {
            Log.d(tag, connectionLostMessage)
            Toast.makeText(context, connectionLostMessage, Toast.LENGTH_LONG).show()
        }
    }

    val startAfter = 100L // in milliseconds

    // Send gamepad state updates periodically
    LaunchedEffect(gamepadState, pollingDelay) {
        delay(startAfter)

        // Start sending updates
        while (connectionViewModel != null && !isStopping.value) {
            // Queue the update in the ViewModel
            connectionViewModel.enqueueGamepadState(gamepadState)

            // Reset ButtonsUp after each update
            gamepadState.ButtonsUp = 0

            // Wait before next update
            delay(pollingDelay)
        }
    }
}

@Composable
private fun GamepadPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(0x66000000)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Preview(
    name = "Design Preview (Light)",
    device = "spec:width=${PreviewWidthDp}dp,height=${PreviewHeightDp}dp,orientation=landscape,dpi=420",
)
@Preview(
    name = "Design Preview (Dark)",
    device = "spec:width=${PreviewWidthDp}dp,height=${PreviewHeightDp}dp,orientation=landscape,dpi=420",
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
)
@Preview(
    name = "Phone - Landscape (Light)",
    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
    showSystemUi = true
)
@Preview(
    name = "Phone - Landscape (Dark)",
    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
)
@Preview(
    name = "Tablet (Light)",
    device = TABLET,
    showSystemUi = true
)
@Preview(
    name = "Tablet (Dark)",
    device = TABLET,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
)
@Preview(
    name = "Desktop (Light)",
    device = DESKTOP,
    showSystemUi = true
)
@Preview(
    name = "Desktop (Dark)",
    device = DESKTOP,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
)
annotation class MultiDevicePreview

@MultiDevicePreview
@Composable
fun GamePadPreview() {
    PreviewBase {
        GamePad(null) {}
    }
}
