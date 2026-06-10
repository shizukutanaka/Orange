package com.orange.apple

import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Apple philosophy: one screen, one button.
 * No feature list. No onboarding slides. No explanation.
 * The button IS the product promise.
 *
 * Rams #8 (thorough detail): the tap is reinforced with one haptic pulse
 * (CONFIRM) on success, and the screen fades before self-destructing so
 * the user sees acknowledgment. Without that beat, tapping feels like
 * the button malfunctioned.
 *
 * Accessibility: the circle carries contentDescription so TalkBack users
 * hear "Start protecting this phone from unwanted calls" instead of
 * the literal word "Protect" without context.
 */
class OnboardingActivity : ComponentActivity() {

    private val fadingOut = mutableStateOf(false)

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) finishToSilent()
        else fadingOut.value = false  // user declined; stay on screen for retry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the user already holds the screening role (e.g. they tapped the
        // widget or re-launched the app), skip the Protect button entirely.
        // Showing a button for a completed action is an anti-pattern Apple
        // never ships: there is no "Set Up Again" screen on an iPhone that's
        // already set up.
        if (RoleMonitor.isRoleHeld(this)) {
            finishToSilent()
            return
        }

        setContent {
            ProtectScreen(
                fadingOut = fadingOut.value,
                onTap = ::requestScreeningRole
            )
        }
    }

    private fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true &&
                !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            ) {
                fadingOut.value = true
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                return
            }
        }
        fadingOut.value = true
        finishToSilent()
    }

    private fun finishToSilent() {
        window.decorView.postDelayed({
            WeeklyDigest.schedule(this)
            // Prompt for family number setup on first launch if not yet prompted.
            if (!FamilyCallback.hasBeenPrompted(this) &&
                FamilyCallback.getNumbers(this).isEmpty()) {
                FamilyCallback.markPrompted(this)
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            finishAndRemoveTask()
        }, 340L)
    }
}

@androidx.compose.runtime.Composable
private fun ProtectScreen(fadingOut: Boolean, onTap: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1.0f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )
    val targetAlpha = if (fadingOut) 0f else 1f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "alpha"
    )
    val view = LocalView.current
    val description = stringResource(R.string.cta_protect_description)

    Box(
        Modifier.fillMaxSize().background(Color(0xFFFF8C42)).alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(220.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = rememberRipple(color = Color(0xFFFF8C42))
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onTap()
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.cta_protect),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF8C42)
            )
        }
    }
}
