package com.nuvio.tv.ui.screens.customtab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabSettingsScreen(
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    onBackPress: () -> Unit = {},
    onNavigateToEdit: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val canAddMore = uiState.tabs.size < 3
    val focusRequester = initialFocusRequester ?: remember { FocusRequester() }
    var showAddTabDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(R.string.custom_tab_settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(top = 40.dp, bottom = 24.dp)
            )

            if (uiState.tabs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.custom_tab_settings_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.custom_tab_settings_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                    if (canAddMore) {
                        Button(
                            onClick = { showAddTabDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Primary,
                                contentColor = NuvioTheme.colors.OnPrimary
                            )
                        ) {
                            Text(stringResource(R.string.custom_tab_add_first), fontSize = 18.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 60.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.tabs, key = { it.id }) { tab ->
                        CustomTabSettingsItem(
                            tab = tab,
                            onEditClick = { onNavigateToEdit(tab.id) },
                            onDeleteClick = { viewModel.removeTab(tab.id) },
                            isFirst = tab.id == uiState.tabs.firstOrNull()?.id
                        )
                    }
                    if (canAddMore) {
                        item {
                            Button(
                                onClick = { showAddTabDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextPrimary
                                )
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary
                                    )
                                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.foundation.layout.Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.custom_tab_add_new),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NuvioTheme.colors.Primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddTabDialog) {
        AddTabDialog(
            onDismiss = { showAddTabDialog = false },
            onConfirm = { name ->
                viewModel.addTab(name)
                showAddTabDialog = false
            }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomTabSettingsItem(
    tab: CustomTab,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    isFirst: Boolean
) {
    val focusRequester = if (isFirst) remember { FocusRequester() } else null
    val deleteFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester ?: androidx.compose.ui.focus.FocusRequester.Default)
            .padding(vertical = 8.dp)
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(8.dp))
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onEditClick,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_edit), fontSize = 14.sp)
                }
                Button(
                    onClick = onDeleteClick,
                    modifier = Modifier.focusRequester(deleteFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.Error
                    )
                ) {
                    Text(stringResource(R.string.action_delete), fontSize = 14.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddTabDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_tab_add_title)) },
        text = {
            androidx.compose.material3.TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(16.dp),
                label = { Text(stringResource(R.string.custom_tab_name_hint)) },
                maxLines = 1,
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = NuvioTheme.colors.OnPrimary
                )
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}