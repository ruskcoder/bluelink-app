package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.GameCatalog
import com.ruskcoder.messages.data.model.GameCatalogEntry
import com.ruskcoder.messages.data.model.gameThumbnailColor

private const val PIGEON_PACKAGE = "com.ruskcoder.messages.games"
private const val PIGEON_STORE_URL =
    "https://play.google.com/store/apps/details?id=com.ruskcoder.messages.games"

/**
 * The games face of the "+" panel — a grid of the games the pigeon app runs.
 *
 * Tapping one sends a game invite as an iMessage balloon (see `IMessageSender.sendGameInvite`);
 * the pigeon app is only involved once the bubble is tapped to actually play. Sending works
 * whether or not pigeon is installed, so the grid is always shown, with a small prompt above it
 * when it is missing.
 */
@Composable
fun GamesPanel(
    onPick: (GameCatalogEntry) -> Unit,
    surface: Color,
    height: Dp = 232.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pigeonInstalled by remember {
        mutableStateOf(runCatching { context.packageManager.getPackageInfo(PIGEON_PACKAGE, 0) }.isSuccess)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(8.dp)
            .frostedFill(RoundedCornerShape(26.dp), tint = surface.copy(alpha = 0.55f))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!pigeonInstalled) {
                InstallPigeonRow(
                    onGet = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(PIGEON_STORE_URL),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onRefresh = {
                        pigeonInstalled = runCatching {
                            context.packageManager.getPackageInfo(PIGEON_PACKAGE, 0)
                        }.isSuccess
                    },
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(GameCatalog) { game ->
                    GameTile(game, onClick = { onPick(game) })
                }
            }
        }
    }
}

/**
 * The games face of the "+" panel when OpenPigeon is the games app.
 *
 * There is no grid to build here, because the game list is not ours: OpenPigeon draws its own
 * picker and hands it over as [android.widget.RemoteViews], and choosing a game in it calls back
 * into the extension, which composes the opening message and gives it to us to send. So this is a
 * frame around somebody else's screen — which is exactly why every game OpenPigeon ships works
 * here without this app knowing a single one of them by name.
 */
@Composable
fun OpenPigeonGamesPanel(
    views: android.widget.RemoteViews?,
    installed: Boolean,
    onGet: () -> Unit,
    onRefresh: () -> Unit,
    surface: Color,
    height: Dp = 232.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(8.dp)
            .frostedFill(RoundedCornerShape(26.dp), tint = surface.copy(alpha = 0.55f))
            .padding(14.dp),
    ) {
        when {
            !installed -> InstallExtensionRow(
                text = "Install OpenPigeon to send and play its games.",
                buttonLabel = "Get OpenPigeon",
                onGet = onGet,
                onRefresh = onRefresh,
            )
            views == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Loading games…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Nothing of ours is drawn over the picker. There is no send button here: choosing a
            // game stages it in the send bar, and the bar sends it — which is also what keeps this
            // panel's hands off OpenPigeon's own header, where a button of ours sat on top of the
            // arrow that turns the page.
            else -> RemoteViewsHost(views, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun InstallExtensionRow(
    text: String,
    buttonLabel: String,
    onGet: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGet, modifier = Modifier.padding(top = 6.dp)) { Text(buttonLabel) }
        Text(
            "Installed? Refresh.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp).clickable(onClick = onRefresh),
        )
    }
}

@Composable
private fun InstallPigeonRow(onGet: () -> Unit, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Install the games app to play. Invites still send without it.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onGet, modifier = Modifier.padding(top = 6.dp)) { Text("Get games app") }
        Text(
            "Installed? Refresh.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp).clickable(onClick = onRefresh),
        )
    }
}

@Composable
private fun GameTile(game: GameCatalogEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp),
    ) {
        // The game's own poster, the same one its bubble carries. The coloured square and
        // controller icon stay underneath as the ground for a game that has no poster.
        val poster = remember(game.id) {
            com.ruskcoder.messages.games.GamePreview.posterUri(context, game.id)
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(gameThumbnailColor(game.id))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = game.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            game.name,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
