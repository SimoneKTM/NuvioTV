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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.CustomTabIcon
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabSettingsScreen(
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToEdit: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Text(
            text = stringResource(R.string.custom_tabs_settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary
        )

        Text(
            text = stringResource(R.string.custom_tabs_settings_subtitle, CustomTab.getMaxTabs()),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )

        if (uiState.tabs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.custom_tabs_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.custom_tabs_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                uiState.tabs.forEachIndexed { index, tab ->
                    CustomTabSettingsCard(
                        tab = tab,
                        index = index,
                        onEditClick = { onNavigateToEdit(tab.id) },
                        onDeleteClick = { viewModel.deleteTab(tab.id) },
                        onMoveUpClick = { if (index > 0) viewModel.moveTab(index, index - 1) },
                        onMoveDownClick = { if (index < uiState.tabs.lastIndex) viewModel.moveTab(index, index + 1) },
                        canMoveUp = index > 0,
                        canMoveDown = index < uiState.tabs.lastIndex
                    )
                }
            }
        }

        if (uiState.tabs.size < CustomTab.getMaxTabs()) {
            Button(
                onClick = { viewModel.showAddTabDialog = true },
                modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.lg),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = NuvioTheme.colors.OnPrimary
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = NuvioTheme.colors.OnPrimary
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                    Text(
                        text = stringResource(R.string.custom_tabs_add_new),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.OnPrimary
                    )
                }
            }
        }
    }

    if (viewModel.showAddTabDialog) {
        AddTabDialog(
            onDismiss = { viewModel.showAddTabDialog = false },
            onAdd = { name, icon ->
                viewModel.addTab(name, icon)
                viewModel.showAddTabDialog = false
            }
        )
    }
}

@Composable
private fun CustomTabSettingsCard(
    tab: CustomTab,
    index: Int,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onMoveUpClick: () -> Unit,
    onMoveDownClick: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = NuvioTheme.colors.Surface,
            contentColor = NuvioTheme.colors.OnSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material.Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = stringResource(R.string.custom_tabs_drag_to_reorder),
                tint = NuvioTheme.colors.TextTertiary,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = NuvioTheme.spacing.md)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = "${index + 1}. ${tab.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = "${tab.sources.size} ${stringResource(R.string.custom_tabs_sources)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Button(
                    onClick = onMoveUpClick,
                    enabled = canMoveUp,
                    modifier = Modifier.size(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NuvioTheme.colors.SurfaceContainerHighest
                    )
                ) {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.custom_tabs_move_up),
                        tint = if (canMoveUp) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary
                    )
                }

                Button(
                    onClick = onMoveDownClick,
                    enabled = canMoveDown,
                    modifier = Modifier.size(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NuvioTheme.colors.SurfaceContainerHighest
                    )
                ) {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.custom_tabs_move_down),
                        tint = if (canMoveDown) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary
                    )
                }

                Button(
                    onClick = onEditClick,
                    modifier = Modifier.size(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NuvioTheme.colors.SurfaceContainerHighest
                    )
                ) {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.custom_tabs_edit),
                        tint = NuvioTheme.colors.TextPrimary
                    )
                }

                Button(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NuvioTheme.colors.ErrorContainer
                    )
                ) {
                    androidx.compose.material.Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.custom_tabs_delete),
                        tint = NuvioTheme.colors.Error
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTabDialog(
    onDismiss: () -> Unit,
    onAdd: (String, CustomTabIcon) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedIcon by remember { mutableStateOf(CustomTabIcon.DEFAULT) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.xl)
                .width(600.dp),
            colors = CardDefaults.cardColors(
                containerColor = NuvioTheme.colors.Surface,
                contentColor = NuvioTheme.colors.OnSurface
            )
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                Text(
                    text = stringResource(R.string.custom_tabs_add_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary
                )

                androidx.compose.material3.TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.custom_tabs_name_label)) },
                    placeholder = { Text(stringResource(R.string.custom_tabs_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1,
                    colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
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
                            selected = selectedIcon == icon,
                            onClick = { selectedIcon = icon }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f, fill = false),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NuvioTheme.colors.SurfaceContainerHighest
                        )
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = { if (name.isNotBlank()) onAdd(name, selectedIcon) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f, fill = false),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NuvioTheme.colors.Primary,
                            contentColor = NuvioTheme.colors.OnPrimary
                        )
                    ) {
                        Text(stringResource(R.string.custom_tabs_add))
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