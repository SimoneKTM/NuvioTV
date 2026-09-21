package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class NuvioIconSizes(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp
)

@Immutable
data class NuvioButtonSizes(
    val compactHeight: Dp,
    val defaultHeight: Dp,
    val largeHeight: Dp,
    val minWidth: Dp
)

@Immutable
data class NuvioSidebarSizes(
    val hiddenWidth: Dp,
    val compactWidth: Dp,
    val closedWidth: Dp,
    val expandedWidth: Dp,
    val expandedItemWidth: Dp,
    val railItemHeight: Dp,
    val leadingVisual: Dp
)

@Immutable
data class NuvioCardSizes(
    val posterWidth: Dp,
    val posterHeight: Dp,
    val posterCompactWidth: Dp,
    val posterCompactHeight: Dp,
    val backdropWidth: Dp,
    val backdropHeight: Dp,
    val episodeWidth: Dp,
    val episodeHeight: Dp
)

@Immutable
data class NuvioAvatarSizes(
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val profile: Dp,
    val profileCompact: Dp
)

@Immutable
data class NuvioPlayerSizes(
    val control: Dp,
    val compactControl: Dp,
    val timelineHeight: Dp,
    val sidePanelWidth: Dp,
    val railWidth: Dp
)

@Immutable
data class NuvioSettingsSizes(
    val railWidth: Dp,
    val railItemHeight: Dp,
    val workspaceMinWidth: Dp,
    val rowMinHeight: Dp
)

@Immutable
data class NuvioSizeTokens(
    val icons: NuvioIconSizes,
    val buttons: NuvioButtonSizes,
    val sidebar: NuvioSidebarSizes,
    val cards: NuvioCardSizes,
    val avatars: NuvioAvatarSizes,
    val player: NuvioPlayerSizes,
    val settings: NuvioSettingsSizes,
    val logoWidth: Dp,
    val logoHeight: Dp,
    val menuItemHeight: Dp
)

object NuvioSizes {
    val tokens = NuvioSizeTokens(
        icons = NuvioIconSizes(
            xs = 14.dp,
            sm = 18.dp,
            md = 22.dp,
            lg = 28.dp,
            xl = 36.dp
        ),
        buttons = NuvioButtonSizes(
            compactHeight = 40.dp,
            defaultHeight = 52.dp,
            largeHeight = 64.dp,
            minWidth = 96.dp
        ),
        sidebar = NuvioSidebarSizes(
            hiddenWidth = 0.dp,
            compactWidth = 54.dp,
            closedWidth = 140.dp,
            expandedWidth = 200.dp,
            expandedItemWidth = 112.dp,
            railItemHeight = 38.dp,
            leadingVisual = 24.dp
        ),
        cards = NuvioCardSizes(
            posterWidth = 108.dp,
            posterHeight = 162.dp,
            posterCompactWidth = 96.dp,
            posterCompactHeight = 144.dp,
            backdropWidth = 280.dp,
            backdropHeight = 158.dp,
            episodeWidth = 280.dp,
            episodeHeight = 180.dp
        ),
        avatars = NuvioAvatarSizes(
            sm = 34.dp,
            md = 48.dp,
            lg = 82.dp,
            xl = 112.dp,
            profile = 126.dp,
            profileCompact = 104.dp
        ),
        player = NuvioPlayerSizes(
            control = 44.dp,
            compactControl = 40.dp,
            timelineHeight = 4.dp,
            sidePanelWidth = 360.dp,
            railWidth = 280.dp
        ),
        settings = NuvioSettingsSizes(
            railWidth = 260.dp,
            railItemHeight = 56.dp,
            workspaceMinWidth = 720.dp,
            rowMinHeight = 64.dp
        ),
        logoWidth = 190.dp,
        logoHeight = 44.dp,
        menuItemHeight = 48.dp
    )
}
