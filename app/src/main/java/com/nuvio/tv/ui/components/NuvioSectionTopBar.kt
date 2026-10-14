package com.nuvio.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

data class TopBarSectionTab(
    val route: String,
    val label: String
)

/**
 * Barra alta delle sezioni — il riferimento è Prime Video: una striscia di
 * tab testuali in alto (Home · Extra · Anime · Calendario) raggiungibile
 * con SU dalla prima riga di contenuti, che si dimmera quando il focus è
 * altrove. A destra l'avatar del profilo (come Netflix/Prime). Le etichette
 * delle sezioni vivono QUI; il rail a sinistra resta a icone.
 *
 * Zero immagini: solo testo, un scrim verticale di leggibilità e
 * ProfileAvatarCircle (avatar già caricato altrove o iniziale).
 */
@Composable
internal fun NuvioSectionTopBar(
    tabs: List<TopBarSectionTab>,
    selectedRoute: String?,
    tabFocusRequesters: Map<String, FocusRequester>,
    showProfile: Boolean,
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    onProfileClick: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var barHasFocus by remember { mutableStateOf(false) }
    val barAlpha by animateFloatAsState(
        targetValue = if (barHasFocus) 1f else 0.72f,
        label = "topBarAlpha"
    )

    Box(
        modifier = modifier.graphicsLayer { alpha = barAlpha }
    ) {
        // Scrim verticale sottile: leggibilità delle tab sopra backdrop chiari.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = NuvioTheme.spacing.lg)
                .onFocusChanged { state -> barHasFocus = state.hasFocus },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            // Marchio "nuvio" a sinistra come su Prime Video: la barra non è
            // solo un tratto di tab ma anche identità di sezione.
            Image(
                painter = painterResource(R.drawable.nuvio_text),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = NuvioTheme.spacing.md)
                    .height(20.dp),
                contentScale = ContentScale.FillHeight
            )
            tabs.forEach { tab ->
                SectionTab(
                    label = tab.label,
                    selected = tab.route == selectedRoute,
                    focusRequester = tabFocusRequesters[tab.route],
                    onClick = { onNavigate(tab.route) }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (showProfile && profileName.isNotBlank()) {
                ProfileAvatarCircle(
                    name = profileName,
                    colorHex = profileColorHex,
                    avatarImageUrl = profileAvatarImageUrl,
                    size = 36.dp,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.sm)
                )
                // L'avatar è cliccabile: un Box overlay clickable della stessa
                // misura evita di duplicare la logica di disegno.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onProfileClick)
                )
            }
        }
    }
}

@Composable
private fun SectionTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val textColor = when {
        focused -> Color.White
        selected -> NuvioTheme.colors.TextPrimary
        else -> NuvioTheme.colors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) NuvioTheme.colors.FocusBackground else Color.Transparent)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state -> focused = state.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            maxLines = 1
        )
    }
}
