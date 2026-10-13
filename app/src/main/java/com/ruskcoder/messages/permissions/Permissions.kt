package com.ruskcoder.messages.permissions

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Whether a permission is currently granted, and a launcher to request it. */
class PermissionRequester(initialGranted: Boolean) {
    var granted: Boolean by mutableStateOf(initialGranted)
        internal set
    internal var requestAction: () -> Unit = {}
    fun request() = requestAction()
}

/**
 * A minimal permission hook: observes whether [permission] is granted and exposes a launcher to
 * request it. Ported from `context/permissions.tsx`'s state machine, without the granted →
 * undetermined → denied → blocked distinction — Android's own system dialog already handles the
 * "don't ask again" case, so this only tracks the boolean that actually gates behavior.
 */
@Composable
fun rememberPermissionState(permission: String): PermissionRequester {
    val context = LocalContext.current
    val initialGranted = remember(permission) {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val requester = remember(permission) { PermissionRequester(initialGranted) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requester.granted = granted
    }
    requester.requestAction = { launcher.launch(permission) }
    return requester
}
