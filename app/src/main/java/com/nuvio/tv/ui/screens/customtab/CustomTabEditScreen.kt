package com.nuvio.tv.ui.screens.customtab

import androidx.compose.foundation.Arrangement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.TextField
import androidx.tv.material3.TextFieldDefaults
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.CustomTabIcon
import com.nuvio.tv.domain.model.CustomTabSource
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabEditScreen(
    tabId: String,
    viewModel: CustomTabEditViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.tab == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.custom_tab_not_found),
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }
        return
    }

    val tab = uiState.tab!!

    Column(
        modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackPress,
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NuvioTheme.colors.SurfaceContainerHighest
                )
            ) {
                androidx.compose.material.Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = NuvioTheme.colors.TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.custom_tab_edit_title, tab.name),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        androidx.compose.material3.TextField(
            value = uiState.editedName,
            onValueChange = { viewModel.updateName(it) },
            label = { Text(stringResource(R.string.custom_tabs_name_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            maxLines = 1,
            colors = TextFieldDefaults.textFieldColors(
                containerColor = NuvioTheme.colors.SurfaceContainerHighest,
                focusedLabelColor = NuvioTheme.colors.Primary,
                unfocusedLabelColor = NuvioTheme.colors.TextSecondary
            )
        )

        Text(
            text = stringResource(R.string.custom_tabs_icon_label),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextSecondary
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            items(CustomTabIcon.values()) { icon ->
                IconOption(
                    icon = icon,
                    selected = uiState.editedIcon == icon,
                    onClick = { viewModel.updateIcon(icon) }
                )
            }
        }

        Text(
            text = stringResource(R.string.custom_tabs_sources_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )

        Text(
            text = stringResource(R.string.custom_tabs_sources_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        if (uiState.availableAddons.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.custom_tabs_no_addons),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                items(uiState.availableAddons) { addon ->
                    AddonSourceCard(
                        addon = addon,
                        selectedSources = tab.sources.filter { it.addonId == addon.id },
                        onSourceToggle = { source, isSelected ->
                            viewModel.toggleSource(source, isSelected)
                        }
                    )
                }
            }
        }

        Button(
            onClick = { viewModel.saveChanges() },
            modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = NuvioTheme.colors.Primary,
                contentColor = NuvioTheme.colors.OnPrimary
            )
        ) {
            Text(
                text = stringResource(R.string.custom_tabs_save),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun AddonSourceCard(
    addon: Addon,
    selectedSources: List<CustomTabSource>,
    onSourceToggle: (CustomTabSource, Boolean) -> Unit
) {
    val catalogs = addon.catalogs ?: emptyList()
    if (catalogs.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = NuvioTheme.colors.Surface,
            contentColor = NuvioTheme.colors.OnSurface
        )
    ) {
        Column(
            modifier = Modifier.padding(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Text(
                text = addon.name,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                items(catalogs) { catalog ->
                    val source = CustomTabSource(
                        addonId = addon.id,
                        type = catalog.type,
                        catalogId = catalog.id
                    )
                    val isSelected = selectedSources.any { s ->
                        s.addonId == source.addonId &&
                        s.type == source.type &&
                        s.catalogId == source.catalogId
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTheme.spacing.sm)
                            .clickable { onSourceToggle(source, !isSelected) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material.Icon(
                            imageVector = if (isSelected)
                                Icons.Default.CheckBox
                            else
                                Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (isSelected)
                                stringResource(R.string.custom_tabs_source_selected)
                            else
                                stringResource(R.string.custom_tabs_source_not_selected),
                            tint = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextTertiary,
                            modifier = Modifier.size(24.dp).padding(end = NuvioTheme.spacing.md)
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = catalog.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Text(
                                text = "${catalog.type} • ${catalog.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconOption(
    icon: CustomTabIcon,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (selected) NuvioTheme.colors.PrimaryContainer else NuvioTheme.colors.SurfaceContainerHighest,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) NuvioTheme.colors.Primary else androidx.compose.ui.graphics.Color.Transparent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.material.Icon(
                imageVector = getMaterialIcon(icon.materialIcon),
                contentDescription = icon.displayName,
                tint = if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = icon.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

private fun getMaterialIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    return when (name) {
        "Default" -> Icons.Default.Movie
        "Movie" -> Icons.Default.Movie
        "LiveTv" -> Icons.Default.LiveTv
        "FilterDrama" -> Icons.Default.FilterDrama
        "VideoLibrary" -> Icons.Default.VideoLibrary
        "ChildFriendly" -> Icons.Default.ChildFriendly
        "SportsEsports" -> Icons.Default.SportsEsports
        "MusicNote" -> Icons.Default.MusicNote
        "Newspaper" -> Icons.Default.Newspaper
        "RocketLaunch" -> Icons.Default.RocketLaunch
        "Skull" -> Icons.Default.SentimentVeryDissatisfied
        "SentimentSatisfied" -> Icons.Default.SentimentSatisfied
        "FlashOn" -> Icons.Default.FlashOn
        "TheaterComedy" -> Icons.Default.TheaterComedy
        "Edit" -> Icons.Default.Edit
        else -> Icons.Default.Movie
    }
}