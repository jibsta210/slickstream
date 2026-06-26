package com.slickstream.tv.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.slickstream.ui.theme.Brand

/** A single destination shown in the left navigation rail. */
enum class TvDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME(com.slickstream.navigation.Routes.HOME, "Home", Icons.Rounded.Home),
    MOVIES(com.slickstream.navigation.Routes.MOVIES, "Movies", Icons.Rounded.Movie),
    TV(com.slickstream.navigation.Routes.TV, "TV", Icons.Rounded.Tv),
    SPORTS(com.slickstream.navigation.Routes.SPORTS, "Sports", Icons.Rounded.SportsBasketball),
    FAVORITES(com.slickstream.navigation.Routes.FAVORITES, "Favourites", Icons.Rounded.Favorite),
    SEARCH(com.slickstream.navigation.Routes.SEARCH, "Search", Icons.Rounded.Search),
    PROFILE(com.slickstream.navigation.Routes.PROFILE, "Profile", Icons.Rounded.Person),
}

/**
 * Left navigation rail for the TV shell. FIXED width with labels always visible — it deliberately
 * does NOT expand on focus, because animating the rail width slid the entire content area sideways
 * on every D-pad move into/out of the rail (a pervasive source of the "choppy" feel). Selecting an
 * item invokes [onSelect] with the destination's route.
 */
@Composable
fun TvNavRail(
    selectedRoute: String,
    onSelect: (TvDestination) -> Unit,
    modifier: Modifier = Modifier,
    /** Attached to the SELECTED item so the shell can land initial app-launch focus on the rail (so it's
     *  obvious you're in the menu) rather than on the home content. */
    selectedItemFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xFF101019),
                    1f to Brand.Background,
                ),
            )
            .width(212.dp)
            .selectableGroup()
            .padding(vertical = 28.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "SlickStream",
            style = MaterialTheme.typography.titleLarge,
            color = Brand.Violet,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
        )

        TvDestination.entries.forEach { dest ->
            TvNavItem(
                destination = dest,
                selected = dest.route == selectedRoute,
                onSelect = { onSelect(dest) },
                focusRequester = if (dest.route == selectedRoute) selectedItemFocus else null,
            )
        }

        Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun TvNavItem(
    destination: TvDestination,
    selected: Boolean,
    onSelect: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val shape = RoundedCornerShape(14.dp)
    val activeTint = when {
        focused -> Color.White
        selected -> Brand.Violet
        else -> Brand.OnSurfaceDim
    }

    Surface(
        onClick = onSelect,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            // selected = subtle fill + violet tint; focused = solid violet pill + white ring.
            containerColor = if (selected) Brand.SurfaceVariant else Color.Transparent,
            focusedContainerColor = Brand.Violet,
            pressedContainerColor = Brand.VioletDim,
            focusedContentColor = Color.White,
            contentColor = activeTint,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1.06f),
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.label,
                    tint = if (focused) Color.White else activeTint,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focused) Color.White else activeTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
