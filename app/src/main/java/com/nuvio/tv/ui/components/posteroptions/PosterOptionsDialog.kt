package com.nuvio.tv.ui.components.posteroptions

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.logo.LogoOverrideStore
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.components.NuvioDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isMovie: Boolean,
    isSeries: Boolean = false,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    onSetCustomLogo: () -> Unit = {},
    onSetCustomPoster: () -> Unit = {},
    onSetCustomBackdrop: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onToggleLibrary,
            enabled = !isLibraryPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(
                if (showManageLists) {
                    stringResource(R.string.library_manage_lists)
                } else {
                    if (isInLibrary) {
                        stringResource(R.string.hero_remove_from_library)
                    } else {
                        stringResource(R.string.hero_add_to_library)
                    }
                }
            )
        }

        if (isMovie || isSeries) {
            Button(
                onClick = onToggleWatched,
                enabled = !isWatchedPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    if (isWatched) {
                        stringResource(R.string.hero_mark_unwatched)
                    } else {
                        stringResource(R.string.hero_mark_watched)
                    }
                )
            }
        }

        // Logo manuale per titoli senza logo TMDB (o per sostituirlo):
        // apre l'editor con il link dell'immagine, salvato per id item.
        Button(
            onClick = onSetCustomLogo,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.poster_action_set_custom_logo))
        }

        // Poster e sfondo hero personalizzati: stessa logica dell'override
        // logo, store separati (PosterOverrideStore / HeroBackdropOverrideStore).
        Button(
            onClick = onSetCustomPoster,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.poster_action_set_custom_poster))
        }

        Button(
            onClick = onSetCustomBackdrop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.poster_action_set_custom_backdrop))
        }
    }
}

/**
 * Editor del logo personalizzato: un campo di testo con il link
 * dell'immagine, più la rimozione dell'override se già presente.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomLogoEditorDialog(
    initialUrl: String,
    hasExisting: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    // Personalizzazione: lo stesso editor serve per logo, poster e sfondo
    // hero ("Imposta … personalizzato" nel menu Azioni Titolo).
    editorTitle: String? = null,
    editorSubtitle: String? = null,
    removeLabel: String? = null
) {
    var input by remember(initialUrl) { androidx.compose.runtime.mutableStateOf(initialUrl) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = editorTitle ?: stringResource(R.string.custom_logo_editor_title),
        subtitle = editorSubtitle ?: stringResource(R.string.custom_logo_editor_subtitle),
        width = 560.dp
    ) {
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    onSave(input)
                }
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(NuvioTheme.colors.Primary),
            decorationBox = { innerTextField ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(10.dp))
                        .border(
                            width = NuvioTheme.spacing.hairline,
                            color = NuvioTheme.colors.Border,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
                ) {
                    if (input.isBlank()) {
                        Text(
                            text = stringResource(R.string.custom_logo_editor_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Due pulsanti per riga con weight(1f): "Rimuovi poster personalizzato"
        // è troppo lungo per stare accanto agli altri in un dialog da 560dp e
        // spingeva "Annulla" a capo ("Annul/la"). Ora ogni etichetta resta su
        // una riga sola e la rimozione occupa una riga intera.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Button(
                onClick = {
                    keyboardController?.hide()
                    onSave(input)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.Primary)
            ) {
                Text(
                    stringResource(R.string.action_save),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    stringResource(R.string.action_cancel),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (hasExisting) {
            Button(
                onClick = {
                    keyboardController?.hide()
                    onRemove()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(
                    removeLabel ?: stringResource(R.string.poster_action_remove_custom_logo),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "✓ ${tab.title}" else tab.title
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Divider(color = NuvioTheme.colors.Border, thickness = NuvioTheme.spacing.hairline)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
fun PosterOptionsHost(
    state: PosterOptionsState,
    controller: PosterOptionsController,
    onNavigateToDetail: (id: String, type: String, addonBaseUrl: String) -> Unit
) {
    val target = state.target
    // Editor immagine personalizzata (logo/poster/sfondo): stato locale, si
    // apre sopra il menu azioni. Quale store usare è scelto dalla voce premuta.
    var editorActive by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf(false) }
    var editorStore by remember(state.target?.id) {
        androidx.compose.runtime.mutableStateOf<com.nuvio.tv.core.logo.UrlOverrideStore?>(null)
    }
    var editorTitle by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var editorSubtitle by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var editorRemoveLabel by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var editorInitial by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf("") }
    var editorHasExisting by remember(state.target?.id) { androidx.compose.runtime.mutableStateOf(false) }

    fun openEditor(
        store: com.nuvio.tv.core.logo.UrlOverrideStore,
        itemId: String,
        title: String?,
        subtitle: String?,
        removeLabel: String?
    ) {
        val current = store.current(itemId)
        editorStore = store
        editorInitial = current.orEmpty()
        editorHasExisting = current != null
        editorTitle = title
        editorSubtitle = subtitle
        editorRemoveLabel = removeLabel
        editorActive = true
    }

    if (target != null) {
        val isMovie = target.apiType.equals("movie", ignoreCase = true)
        val isSeries = target.apiType.equals("series", ignoreCase = true) ||
            target.apiType.equals("tv", ignoreCase = true) ||
            target.apiType.equals("anime", ignoreCase = true)
        // Stringhe risolte in composizione: le lambda di onClick non sono
        // composable, quindi non possono chiamare stringResource al volo.
        val posterEditorTitle = androidx.compose.ui.res.stringResource(R.string.custom_poster_editor_title)
        val posterEditorSubtitle = androidx.compose.ui.res.stringResource(R.string.custom_poster_editor_subtitle)
        val posterEditorRemove = androidx.compose.ui.res.stringResource(R.string.poster_action_remove_custom_poster)
        val backdropEditorTitle = androidx.compose.ui.res.stringResource(R.string.custom_backdrop_editor_title)
        val backdropEditorSubtitle = androidx.compose.ui.res.stringResource(R.string.custom_backdrop_editor_subtitle)
        val backdropEditorRemove = androidx.compose.ui.res.stringResource(R.string.poster_action_remove_custom_backdrop)
        PosterOptionsDialog(
            title = target.name,
            isInLibrary = state.isInLibrary,
            isLibraryPending = state.isLibraryPending,
            showManageLists = state.librarySourceMode != LibrarySourceMode.LOCAL,
            isMovie = isMovie,
            isSeries = isSeries,
            isWatched = state.isWatched,
            isWatchedPending = state.isWatchedPending,
            onDismiss = { controller.dismiss() },
            onDetails = {
                onNavigateToDetail(target.id, target.apiType, state.addonBaseUrl)
                controller.dismiss()
            },
            onToggleLibrary = {
                if (state.librarySourceMode != LibrarySourceMode.LOCAL) {
                    controller.openListPicker()
                } else {
                    controller.toggleLibrary()
                    controller.dismiss()
                }
            },
            onToggleWatched = {
                if (isMovie) {
                    controller.toggleMovieWatched()
                } else {
                    controller.toggleSeriesWatched()
                }
                controller.dismiss()
            },
            onSetCustomLogo = {
                openEditor(
                    LogoOverrideStore,
                    target.id,
                    null,
                    null,
                    null
                )
            },
            onSetCustomPoster = {
                openEditor(
                    com.nuvio.tv.core.logo.PosterOverrideStore,
                    target.id,
                    posterEditorTitle,
                    posterEditorSubtitle,
                    posterEditorRemove
                )
            },
            onSetCustomBackdrop = {
                openEditor(
                    com.nuvio.tv.core.logo.HeroBackdropOverrideStore,
                    target.id,
                    backdropEditorTitle,
                    backdropEditorSubtitle,
                    backdropEditorRemove
                )
            }
        )

        if (editorActive) {
            val store = editorStore
            CustomLogoEditorDialog(
                initialUrl = editorInitial,
                hasExisting = editorHasExisting,
                editorTitle = editorTitle,
                editorSubtitle = editorSubtitle,
                removeLabel = editorRemoveLabel,
                onSave = { url ->
                    store?.set(target.id, url)
                    editorActive = false
                },
                onRemove = {
                    store?.set(target.id, null)
                    editorActive = false
                },
                onDismiss = { editorActive = false }
            )
        }
    }

    if (state.listPickerActive) {
        val localTab = LibraryListTab(
            key = LOCAL_LIBRARY_LIST_KEY,
            title = stringResource(R.string.trakt_library_source_nuvio),
            type = LibraryListTab.Type.WATCHLIST
        )
        val contentType = state.listPickerContentType.orEmpty()
        val destinationTabs = state.libraryListTabs.filter { tab ->
            tab.supportsMembershipFor(contentType)
        }
        PosterListPickerDialog(
            title = state.listPickerTitle.orEmpty(),
            tabs = listOf(localTab) + destinationTabs,
            membership = state.listPickerMembership,
            isPending = state.listPickerPending,
            error = state.listPickerError,
            onToggle = { key -> controller.toggleListMembership(key) },
            onSave = { controller.saveListPicker() },
            onDismiss = { controller.dismissListPicker() }
        )
    }

    val confirmations = state.removalConfirmations
    if (confirmations.isNotEmpty()) {
        TrackingRemovalConfirmationDialog(
            itemTitle = state.listPickerTitle.orEmpty(),
            confirmations = confirmations,
            isPending = state.listPickerPending,
            onConfirm = controller::confirmDestructiveRemoval,
            onDismiss = controller::cancelDestructiveRemoval
        )
    }
}

@Composable
fun TrackingRemovalConfirmationDialog(
    itemTitle: String,
    confirmations: List<TrackingMembershipRemovalConfirmation>,
    isPending: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val providers = confirmations.joinToString { confirmation ->
        confirmation.providerId.storageId.replaceFirstChar(Char::uppercase)
    }
    val impacts = confirmations.flatMapTo(linkedSetOf()) { confirmation -> confirmation.impacts }
    val impact = when (impacts) {
        setOf(TrackingMembershipRemovalImpact.WATCHED_HISTORY) ->
            stringResource(R.string.tracking_removal_impact_history)
        setOf(TrackingMembershipRemovalImpact.RATING) ->
            stringResource(R.string.tracking_removal_impact_rating)
        else -> stringResource(R.string.tracking_removal_impact_history_rating)
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_removal_title, providers),
        subtitle = stringResource(R.string.tracking_removal_message, itemTitle, providers, impact),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onConfirm,
            enabled = !isPending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_remove_anyway))
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}
