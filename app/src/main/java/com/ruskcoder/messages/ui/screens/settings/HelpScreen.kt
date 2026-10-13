package com.ruskcoder.messages.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow

/** Where the two support rows go. */
private const val HELP_URL = "https://github.com/ruskcoder/messages"
private const val TERMS_URL = "https://github.com/ruskcoder/messages/blob/main/LICENSE"
private const val CONTACT_URL = "https://github.com/ruskcoder/messages/issues/new"

/** Ported from `settings/help.tsx`. Every row here opens something real. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0.0"

    fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A device with no browser at all is rare but not impossible; better a no-op than a crash.
        runCatching { context.startActivity(intent) }.onFailure { if (it !is ActivityNotFoundException) throw it }
    }

    SettingsPage(title = "Help", onBack = onBack) {
        SettingsGroup(title = "Support") {
            SettingsRow(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = "Help centre",
                chevron = true,
                onClick = { open(HELP_URL) },
            )
            SettingsRow(
                icon = Icons.Outlined.QuestionAnswer,
                title = "Contact us",
                chevron = true,
                onClick = { open(CONTACT_URL) },
            )
        }
        SettingsGroup(
            title = "About",
        ) {
            SettingsRow(
                icon = Icons.Outlined.Description,
                title = "Terms and Privacy Policy",
                chevron = true,
                onClick = { open(TERMS_URL) },
            )
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = "App info",
                description = "Version $version",
                chevron = true,
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )
        }
    }
}

private fun String.toUri(): Uri = Uri.parse(this)
