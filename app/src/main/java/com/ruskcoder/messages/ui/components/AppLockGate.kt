package com.ruskcoder.messages.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.security.AppLock

/**
 * The app's content, behind [AppLock] whenever it is locked.
 *
 * The content stays composed underneath rather than being torn down: unlocking should put back the
 * conversation exactly as it was left, half-typed draft and scroll position included. What covers it
 * is opaque and swallows every touch, so nothing behind it can be read or pressed.
 */
@Composable
fun AppLockGate(lock: AppLock, content: @Composable () -> Unit) {
    val locked by lock.locked.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (locked) LockScreen(lock)
    }
}

@Composable
private fun LockScreen(lock: AppLock) {
    val activity = LocalContext.current as? FragmentActivity
    fun prompt() {
        activity ?: return
        AppLock.authenticate(activity, onUnlocked = lock::unlock, onUnavailable = lock::unlock)
    }
    // Straight to the prompt: coming back to a locked app is a request to unlock it.
    LaunchedEffect(Unit) { prompt() }
    // Back from the lock leaves the app rather than reaching anything underneath it.
    BackHandler { activity?.moveTaskToBack(true) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Messages is locked", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Unlock with your fingerprint, face or screen lock.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = ::prompt) { Text("Unlock") }
    }
}
