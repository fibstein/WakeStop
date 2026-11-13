package com.fibstein.wakestop.ui.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fibstein.wakestop.appContainer
import com.fibstein.wakestop.tracking.LocationTrackingService
import com.fibstein.wakestop.tracking.TripStatus
import com.fibstein.wakestop.ui.theme.WakeStopTheme
import kotlin.math.roundToInt

/** Full-screen alarm shown above the lock screen. Swipe to dismiss. */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Back must not silently dismiss the alarm.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val trips = appContainer.trips
        setContent {
            WakeStopTheme {
                val status by trips.status.collectAsStateWithLifecycle()
                LaunchedEffect(status) {
                    // Dismissed elsewhere (notification action / app) or never alarmed.
                    if (status !is TripStatus.Alarming) finish()
                }
                val name = (status as? TripStatus.Alarming)?.trip?.name.orEmpty()
                AlarmContent(name) {
                    LocationTrackingService.dismissAlarm(this)
                    finish()
                }
            }
        }
    }
}

@Composable
private fun AlarmContent(name: String, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B1F4D)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Alarm, null, tint = Color.White, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                "Durağına yaklaştın!",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(name.ifBlank { "Varış noktası" }, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, textAlign = TextAlign.Center)
        }
        SwipeToDismiss(onDismiss, Modifier.fillMaxWidth().padding(bottom = 48.dp))
    }
}

@Composable
private fun SwipeToDismiss(onDismiss: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .height(72.dp)
            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(36.dp)),
    ) {
        val density = LocalDensity.current
        val thumb = 64.dp
        val maxPx = with(density) { (maxWidth - thumb - 8.dp).toPx() }
        var offset by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableFloatStateOf(0f) }
        val shown by animateFloatAsState(if (dragging > 0f) offset else 0f, label = "thumb")
        Text(
            "Kapatmak için kaydır",
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
        )
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset((if (dragging > 0f) offset else shown).roundToInt(), 0) }
                .size(thumb)
                .background(Color.White, CircleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(0f, maxPx) },
                    onDragStarted = { dragging = 1f },
                    onDragStopped = {
                        if (offset >= maxPx * 0.85f) {
                            onDismiss()
                        } else {
                            offset = 0f
                        }
                        dragging = 0f
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Kapat", tint = Color(0xFF0B1F4D))
        }
    }
}
