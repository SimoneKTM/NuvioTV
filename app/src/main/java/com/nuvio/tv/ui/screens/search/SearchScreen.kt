package com.nuvio.tv.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CardDepthSurface
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.nuvioCardDepth
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.RtlKeyUtils
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.recompositionHighlighter
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Skeleton rows shown while a search is pending, matching the two mobile renders. */
private const val SEARCH_SKELETON_ROW_COUNT = 2

/**
 * Width of the WuPlay-style left panel: a 6-key row (6 * 34dp) plus key gaps and
 * screen padding, so keys keep the reference app's proportions.
 */
private val SEARCH_LEFT_PANEL_WIDTH = SearchVirtualKeyboardKeySize * 6 + SearchVirtualKeyboardKeyGap * 5 + 16.dp * 2

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> },
    onOpenDiscover: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val strVoiceNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strVoiceMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strVoiceFailed = stringResource(R.string.search_voice_failed)
    val strVoiceUnavailable = stringResource(R.string.search_voice_unavailable)
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    val recentClearHistoryFocusRequester = remember { FocusRequester() }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var isRecentSearchSectionFocused by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceRmsLevel by remember { mutableStateOf(0f) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    val restoringSearchFocus = remember { mutableStateOf(viewModel.hasSavedSearchFocus) }
    val didRestoreSearchFocus = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
        }
    }
    val isVoiceSearchAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val speechRecognizer = remember(context, isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    val buildRecognizeIntent: () -> Intent = {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val hasRecordAudioPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioPermissionGranted = granted
        if (granted) {
            isVoiceListening = true
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(hasRecordAudioPermission) {
        recordAudioPermissionGranted = hasRecordAudioPermission
    }
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS dB to 0..1 range. Typical values: -2 (silence) to 10 (loud).
                voiceRmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                Log.w("SearchScreen", "Voice recognition error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(context, strVoiceFailed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                onVoiceQueryResultState.value(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
        }

        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        }
    }
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val launchVoiceSearch: () -> Unit = {
        if (!isVoiceSearchAvailable || speechRecognizer == null) {
            Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
        } else if (!recordAudioPermissionGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            isVoiceListening = true
            runCatching {
                speechRecognizer.cancel()
                speechRecognizer.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val posterCardStyle = remember(uiState.posterCardCornerRadiusDp) {
        PosterCardStyle(
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }

    // Focus restore bookkeeping for the results grid — mirrors ClassicHomeContent pattern so
    // the grid keeps focus when placeholder→real data transitions.
    val searchRowFocusedItemIndex = remember { mutableMapOf<String, Int>() }
    var lastFocusedRowKey by remember { mutableStateOf(viewModel.savedFocusRowKey) }
    val keyboardFirstKeyFocusRequester = remember { FocusRequester() }
    val resultsFirstItemFocusRequester = remember { FocusRequester() }
    var isKeyboardFocusActive by remember { mutableStateOf(false) }

    // Clean up stale keys when the catalog rows change.
    val visibleRowKeys = remember(uiState.catalogRows) {
        uiState.catalogRows.mapTo(mutableSetOf()) {
            it.stableKey()
        }
    }
    // Stable list of non-empty catalog rows — mirrors ClassicHomeContent's
    // visibleHomeRows pattern so the LazyColumn receives a remember'd list.
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    LaunchedEffect(visibleRowKeys) {
        searchRowFocusedItemIndex.keys.retainAll(visibleRowKeys)
    }

    val isDiscoverMode = remember(uiState.discoverLocation, trimmedQuery, trimmedSubmittedQuery) {
        shouldShowDiscoverInSearch(
            discoverLocation = uiState.discoverLocation,
            query = trimmedQuery,
            submittedQuery = trimmedSubmittedQuery
        )
    }
    LaunchedEffect(isDiscoverMode) {
        if (isDiscoverMode) viewModel.ensureDiscoverLoaded()
    }
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode &&
            trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedQuery != trimmedSubmittedQuery
    }
    val showRecentSearches = remember(
        trimmedQuery,
        uiState.recentSearches
    ) {
        trimmedQuery.isEmpty() &&
            uiState.recentSearches.isNotEmpty()
    }
    val canMoveToResults = remember(
        isDiscoverMode,
        uiState.discoverResults,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) {
            false
        } else {
            trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                uiState.catalogRows.any { it.items.isNotEmpty() }
        }
    }
    val submitCurrentQuery: (String) -> Unit = { submittedQuery ->
        viewModel.onEvent(SearchEvent.SubmitSearch)
        focusResults = false
        if (submittedQuery.length >= MIN_SEARCH_QUERY_LENGTH) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }
    val handleQueryChanged: (String) -> Unit = { nextQuery ->
        val previousQuery = uiState.query.trim()
        val trimmedNextQuery = nextQuery.trim()
        val selectedSuggestion = trimmedNextQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedNextQuery != trimmedSubmittedQuery &&
            uiState.suggestions.any { it.equals(trimmedNextQuery, ignoreCase = true) } &&
            trimmedNextQuery.startsWith(previousQuery, ignoreCase = true) &&
            trimmedNextQuery.length - previousQuery.length > 1

        focusResults = false
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
        viewModel.onEvent(SearchEvent.QueryChanged(nextQuery))
        if (selectedSuggestion) {
            submitCurrentQuery(trimmedNextQuery)
        }
    }
    val submitRecentSearch: (String) -> Unit = { recentQuery ->
        val trimmedRecentQuery = recentQuery.trim()
        if (trimmedRecentQuery.isNotEmpty()) {
            viewModel.onEvent(SearchEvent.QueryChanged(trimmedRecentQuery))
            submitCurrentQuery(trimmedRecentQuery)
        }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            delay(100)
            runCatching { discoverFirstItemFocusRequester.requestFocus() }
            focusResults = false
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults,
        isDiscoverMode
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        if (isDiscoverMode) {
            focusResults = true
        } else {
            // Use explicit first-item focus for deterministic landing on row 1 / column 1.
            delay(80)
            focusResults = true
        }
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    LaunchedEffect(Unit) {
        if (viewModel.hasSavedSearchFocus) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { topInputFocusRequester.requestFocus() }
    }

    // Push search suggestions to the native keyboard suggestion bar
    LaunchedEffect(uiState.suggestions) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@LaunchedEffect
        val completions = uiState.suggestions.mapIndexed { index, name ->
            CompletionInfo(index.toLong(), index, name)
        }.toTypedArray()
        imm.displayCompletions(view, completions)
    }

    var isScreenActive by remember { mutableStateOf(true) }
    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || isVoiceListening
    )
    val latestVoiceSearchAvailable by rememberUpdatedState(isVoiceSearchAvailable)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenActive = true
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (viewModel.hasSavedSearchFocus || didRestoreSearchFocus.value) {
                    // Returning from details — don't steal focus, CatalogRowSection
                    // already restored it or will restore it via focusedItemIndex.
                    didRestoreSearchFocus.value = false
                } else if (!latestShouldKeepSearchFocus) {
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching {
                            if (latestVoiceSearchAvailable) {
                                voiceFocusRequester.requestFocus()
                            } else {
                                searchFocusRequester.requestFocus()
                            }
                        }
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isScreenActive = false
                keyboardController?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .recompositionHighlighter()
            .dpadRepeatThrottle()
    ) {
        // Left: search field + WuPlay-style virtual keyboard, always laid out even when the
        // keyboard is collapsed so the field keeps a stable home for focus restoration.
        Column(
            modifier = Modifier
                .width(SEARCH_LEFT_PANEL_WIDTH)
                .fillMaxHeight()
                .padding(start = NuvioTheme.spacing.lg)
        ) {
            SearchInputField(
                query = uiState.query,
                canMoveToResults = canMoveToResults,
                voiceFocusRequester = if (isVoiceSearchAvailable) voiceFocusRequester else null,
                searchFocusRequester = searchFocusRequester,
                onSearchFieldFocusChanged = { focused -> isSearchFieldFocused = focused },
                onQueryChanged = handleQueryChanged,
                onSubmit = {
                    submitCurrentQuery(uiState.query.trim())
                },
                showVoiceSearch = false,
                isVoiceListening = isVoiceListening,
                voiceRmsLevel = voiceRmsLevel,
                onVoiceSearch = launchVoiceSearch,
                onMoveToResults = { focusResults = true },
                onMoveToKeyboard = {
                    runCatching { keyboardFirstKeyFocusRequester.requestFocus() }
                },
                onOpenDiscover = onOpenDiscover,
                showDiscoverButton = false,
                keyboardController = keyboardController,
                clearHistoryFocusRequester = if (showRecentSearches) recentClearHistoryFocusRequester else null,
                isScreenActive = isScreenActive,
                horizontalPadding = NuvioTheme.spacing.md
            )

            AnimatedVisibility(
                visible = isSearchFieldFocused || isKeyboardFocusActive,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                SearchVirtualKeyboard(
                    onKey = { key -> handleQueryChanged(uiState.query + key) },
                    onSpace = { handleQueryChanged(uiState.query + " ") },
                    onBackspace = { handleQueryChanged(uiState.query.dropLast(1)) },
                    onEnter = {
                        submitCurrentQuery(uiState.query.trim())
                        focusResults = true
                    },
                    firstKeyFocusRequester = keyboardFirstKeyFocusRequester,
                    resultsFocusRequester = resultsFirstItemFocusRequester,
                    onFocusChanged = { focused -> isKeyboardFocusActive = focused },
                    modifier = Modifier.padding(top = NuvioTheme.spacing.lg)
                )

                if (uiState.suggestions.isNotEmpty()) {
                    SearchSuggestionsColumn(
                        suggestions = uiState.suggestions,
                        onSuggestionClick = { suggestion ->
                            viewModel.onEvent(SearchEvent.QueryChanged(suggestion))
                            submitCurrentQuery(suggestion)
                        },
                        modifier = Modifier.padding(top = NuvioTheme.spacing.lg)
                    )
                }
            }

            // Secondary actions that used to live in the input row, now below the search box so
            // the input itself can span the whole panel like the reference app.
            Row(
                modifier = Modifier.padding(top = NuvioTheme.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                if (isVoiceSearchAvailable) {
                    IconButton(
                        onClick = launchVoiceSearch,
                        modifier = Modifier
                            .size(NuvioTheme.spacing.huge)
                            .border(
                                width = if (isVoiceListening) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                                color = if (isVoiceListening) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border,
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                            .background(
                                color = NuvioTheme.colors.BackgroundCard,
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.cd_voice_search),
                            tint = if (isVoiceListening) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary
                        )
                    }
                }
                if (uiState.discoverLocation == DiscoverLocation.IN_SEARCH) {
                    IconButton(
                        onClick = onOpenDiscover,
                        modifier = Modifier
                            .size(NuvioTheme.spacing.huge)
                            .border(
                                width = NuvioTheme.spacing.hairline,
                                color = NuvioTheme.colors.Border,
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                            .background(
                                color = NuvioTheme.colors.BackgroundCard,
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = stringResource(R.string.cd_open_discover),
                            tint = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }

        val savedResultsScroll = remember(viewModel.hasSavedSearchFocus) {
            if (viewModel.hasSavedSearchFocus) viewModel.savedResultsScrollPosition else null
        }
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = savedResultsScroll?.first ?: 0,
            initialFirstVisibleItemScrollOffset = savedResultsScroll?.second ?: 0
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .recompositionHighlighter()
                .dpadRepeatThrottle(),
            state = listState,
            contentPadding = PaddingValues(
                top = if (isDiscoverMode) 10.dp else NuvioTheme.spacing.lg,
                bottom = NuvioTheme.spacing.lg
            ),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {

            if (isDiscoverMode) {
                if (showRecentSearches) {
                    item(key = "recent_searches") {
                        RecentSearchesSection(
                            recentSearches = uiState.recentSearches,
                            onSearchSelected = submitRecentSearch,
                            onClearHistory = {
                                viewModel.onEvent(SearchEvent.ClearRecentSearches)
                            },
                            onSectionFocusChanged = { focused -> isRecentSearchSectionFocused = focused },
                            clearHistoryFocusRequester = recentClearHistoryFocusRequester,
                            modifier = Modifier.padding(horizontal = 52.dp)
                        )
                    }
                } else {
                    item(key = "search_start") {
                        EmptyScreenState(
                            title = stringResource(R.string.search_start_title),
                            subtitle = stringResource(R.string.search_start_subtitle),
                            icon = Icons.Default.Search
                        )
                    }
                }
            } else {
                // The "press Done to search" hint is gone: search now runs as you type, so the
                // instruction is wrong, and it was re-appearing on every keystroke. Neither the
                // mobile nor the desktop client shows an equivalent message.

                when {
                    trimmedSubmittedQuery.length < MIN_SEARCH_QUERY_LENGTH && !hasPendingUnsubmittedQuery -> {
                        item {
                            if (showRecentSearches) {
                                RecentSearchesSection(
                                    recentSearches = uiState.recentSearches,
                                    onSearchSelected = submitRecentSearch,
                                    onClearHistory = {
                                        viewModel.onEvent(SearchEvent.ClearRecentSearches)
                                    },
                                    onSectionFocusChanged = { focused ->
                                        isRecentSearchSectionFocused = focused
                                    },
                                    clearHistoryFocusRequester = recentClearHistoryFocusRequester,
                                    modifier = Modifier.padding(horizontal = 52.dp)
                                )
                            } else {
                                EmptyScreenState(
                                    title = stringResource(R.string.search_start_title),
                                    subtitle = if (uiState.discoverLocation == DiscoverLocation.OFF) {
                                        stringResource(R.string.search_start_subtitle_no_discover)
                                    } else {
                                        stringResource(R.string.search_start_subtitle)
                                    },
                                    icon = Icons.Default.Search
                                )
                            }
                        }
                    }

                    // Nothing to show yet, either still waiting on the debounce or on the first
                    // responses. Mobile renders skeleton rows for both, so this does too. Unlike
                    // mobile it only applies with an empty screen: mobile swaps results out for
                    // skeletons on every keystroke, which on a remote reads as flicker because each
                    // letter outlasts the debounce, so existing results are kept instead.
                    (hasPendingUnsubmittedQuery || uiState.isSearching) && visibleCatalogRows.isEmpty() -> {
                        items(SEARCH_SKELETON_ROW_COUNT, key = { "search_skeleton_$it" }) { index ->
                            val skeletonRow = remember(index) {
                                com.nuvio.tv.domain.model.CatalogRow(
                                    addonId = "__skeleton",
                                    addonName = "",
                                    addonBaseUrl = "",
                                    catalogId = "skeleton_$index",
                                    catalogName = "",
                                    type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                    items = (0 until 8).map { i ->
                                        com.nuvio.tv.domain.model.MetaPreview(
                                            id = "__placeholder_skeleton_${index}_$i",
                                            type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                            name = " ",
                                            poster = com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL,
                                            posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                                            background = null,
                                            logo = null,
                                            description = null,
                                            releaseInfo = " ",
                                            imdbRating = null,
                                            genres = emptyList()
                                        )
                                    },
                                    isLoading = true
                                )
                            }
                            SearchResultsGridSection(
                                catalogRow = skeletonRow,
                                onItemClick = { _, _, _ -> },
                                posterCardStyle = posterCardStyle,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                interactive = false,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )
                        }
                    }

                    uiState.error != null && uiState.catalogRows.isEmpty() -> {
                        item {
                            ErrorState(
                                message = uiState.error ?: stringResource(R.string.search_error_failed),
                                onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                            )
                        }
                    }

                    !uiState.isSearching && !hasPendingUnsubmittedQuery && visibleCatalogRows.isEmpty() -> {
                        item {
                            EmptyScreenState(
                                title = stringResource(R.string.search_no_results_title),
                                subtitle = stringResource(R.string.search_no_results_subtitle),
                                icon = Icons.Default.Search
                            )
                        }
                    }

                    else -> {
                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.stableKey()}_$index"
                            },
                            contentType = { _, _ -> "catalog_row" }
                        ) { index, catalogRow ->
                            val catalogKey = catalogRow.stableKey()
                            val isPlaceholder = catalogRow.isLoading &&
                                catalogRow.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                            val hasEnoughForSeeAll = !isPlaceholder && catalogRow.items.size >= 15

                            SearchResultsGridSection(
                                catalogRow = catalogRow,
                                showSeeAll = hasEnoughForSeeAll,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                                entryFocusRequester = if (index == 0) resultsFirstItemFocusRequester else null,
                                restorerFocusedIndex = if (restoringSearchFocus.value && catalogKey == viewModel.savedFocusRowKey) {
                                    viewModel.savedFocusItemIndex
                                } else {
                                    searchRowFocusedItemIndex[catalogKey] ?: -1
                                },
                                isItemWatched = { item ->
                                    val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                                    if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                                },
                                focusedItemIndex = when {
                                    restoringSearchFocus.value && catalogKey == viewModel.savedFocusRowKey ->
                                        viewModel.savedFocusItemIndex
                                    focusResults && index == 0 -> 0
                                    else -> -1
                                },
                                onItemFocused = { itemIndex ->
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                    if (restoringSearchFocus.value) {
                                        restoringSearchFocus.value = false
                                        didRestoreSearchFocus.value = true
                                        viewModel.hasSavedSearchFocus = false
                                    }
                                    // User manually navigated to a row — cancel any
                                    // pending auto-focus so it doesn't steal focus later.
                                    pendingFocusMoveToResultsQuery = null
                                    searchRowFocusedItemIndex[catalogKey] = itemIndex
                                    lastFocusedRowKey = catalogKey
                                },
                                onItemClick = { id, type, addonBaseUrl ->
                                    lastFocusedRowKey = catalogKey
                                    // Save focus state to ViewModel before navigating
                                    viewModel.savedFocusRowKey = catalogKey
                                    viewModel.savedFocusItemIndex = searchRowFocusedItemIndex[catalogKey] ?: 0
                                    viewModel.savedResultsScrollPosition =
                                        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                    viewModel.hasSavedSearchFocus = true
                                    val clickedItem = catalogRow.items.firstOrNull { it.id == id }
                                    HeroBackdropState.update(clickedItem?.backdropUrl)
                                    onNavigateToDetail(id, type, addonBaseUrl)
                                },
                                onItemLongPress = { item, addonBaseUrl ->
                                    viewModel.posterOptions.show(item, addonBaseUrl)
                                },
                                onSeeAll = {
                                    onNavigateToSeeAll(
                                        catalogRow.catalogId,
                                        catalogRow.addonId,
                                        catalogRow.apiType
                                    )
                                }
                            )
                        }

                        // Results are up but more catalogs are still answering, as on mobile.
                        if (uiState.isSearching || hasPendingUnsubmittedQuery) {
                            item(key = "search_loading_more") {
                                val skeletonRow = remember {
                                    com.nuvio.tv.domain.model.CatalogRow(
                                        addonId = "__skeleton",
                                        addonName = "",
                                        addonBaseUrl = "",
                                        catalogId = "skeleton_more",
                                        catalogName = "",
                                        type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                        items = (0 until 8).map { i ->
                                            com.nuvio.tv.domain.model.MetaPreview(
                                                id = "__placeholder_skeleton_more_$i",
                                                type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                                name = " ",
                                                poster = com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL,
                                                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                                                background = null,
                                                logo = null,
                                                description = null,
                                                releaseInfo = " ",
                                                imdbRating = null,
                                                genres = emptyList()
                                            )
                                        },
                                        isLoading = true
                                    )
                                }
                                SearchResultsGridSection(
                                    catalogRow = skeletonRow,
                                    onItemClick = { _, _, _ -> },
                                    posterCardStyle = posterCardStyle,
                                    showAddonName = uiState.catalogAddonNameEnabled,
                                    interactive = false,
                                    modifier = Modifier.padding(bottom = 24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            val clickedItem = uiState.catalogRows
                .flatMap { it.items }
                .firstOrNull { it.id == id }
                ?: uiState.discoverResults.firstOrNull { it.id == id }
            HeroBackdropState.update(clickedItem?.backdropUrl)
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchSuggestionsColumn(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.search_suggestions_title),
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        suggestions.take(4).forEach { suggestion ->
            var isFocused by remember { mutableStateOf(false) }
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTheme.spacing.huge)
                    .background(
                        color = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                    .border(
                        width = if (isFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                    .focusProperties { canFocus = true }
                    .onFocusChanged { state -> isFocused = state.isFocused }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                            (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            onSuggestionClick(suggestion)
                            true
                        } else {
                            false
                        }
                    }
                    .padding(horizontal = NuvioTheme.spacing.xl)
            ) {
                Text(
                    text = suggestion,
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class
)
@Composable
private fun SearchResultsGridSection(
    catalogRow: CatalogRow,
    onItemClick: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: () -> Unit = {},
    showSeeAll: Boolean = catalogRow.hasMore || catalogRow.items.size >= 15,
    seeAllLabel: String? = null,
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    showPosterLabels: Boolean = true,
    showAddonName: Boolean = true,
    showCatalogTypeSuffix: Boolean = true,
    isItemWatched: (MetaPreview) -> Boolean = { false },
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    focusedItemIndex: Int = -1,
    restorerFocusedIndex: Int = -1,
    onItemFocused: (itemIndex: Int) -> Unit = {},
    entryFocusRequester: FocusRequester? = null,
    interactive: Boolean = true
) {
    fun rowItemFocusKey(index: Int, item: MetaPreview): String {
        return catalogRow.stableItemKey(index)
    }

    val itemFocusRequestersByKey = remember { mutableMapOf<String, FocusRequester>() }
    var lastRequestedFocusItemKey by remember { mutableStateOf<String?>(null) }
    var lastFocusedItemIndex by remember { mutableIntStateOf(-1) }

    val latestOnItemClick by rememberUpdatedState(onItemClick)
    val latestOnSeeAll by rememberUpdatedState(onSeeAll)
    val latestIsItemWatched by rememberUpdatedState(isItemWatched)
    val latestOnItemLongPress by rememberUpdatedState(onItemLongPress)
    val latestOnItemFocused by rememberUpdatedState(onItemFocused)

    LaunchedEffect(catalogRow.items) {
        val validKeys = catalogRow.items.mapIndexedTo(mutableSetOf()) { index, item ->
            rowItemFocusKey(index, item)
        }
        itemFocusRequestersByKey.keys.retainAll(validKeys)
        if (lastRequestedFocusItemKey !in validKeys) {
            lastRequestedFocusItemKey = null
        }
    }

    // Restore focus from saved state when focusedItemIndex is set.
    LaunchedEffect(focusedItemIndex, catalogRow.items) {
        if (!interactive) return@LaunchedEffect
        if (focusedItemIndex >= 0 && focusedItemIndex < catalogRow.items.size) {
            val targetItem = catalogRow.items[focusedItemIndex]
            val targetItemKey = rowItemFocusKey(focusedItemIndex, targetItem)
            if (lastRequestedFocusItemKey == targetItemKey) return@LaunchedEffect
            val requester = itemFocusRequestersByKey.getOrPut(targetItemKey) { FocusRequester() }
            repeat(2) { withFrameNanos { } }
            val focused = runCatching { requester.requestFocus() }.isSuccess
            if (focused) {
                lastRequestedFocusItemKey = targetItemKey
            }
        } else {
            lastRequestedFocusItemKey = null
        }
    }

    val catalogContext = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, catalogContext) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(catalogContext, raw)
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = catalogTitle.ifBlank { " " },
                    style = androidx.tv.material3.MaterialTheme.typography.headlineMedium,
                    color = if (catalogTitle.isBlank()) Color.Transparent else NuvioTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
                if (showAddonName) {
                    Text(
                        text = if (catalogTitle.isBlank()) " " else stringResource(R.string.catalog_from_addon, catalogRow.addonName),
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        color = if (catalogTitle.isBlank()) Color.Transparent else NuvioTheme.colors.TextTertiary
                    )
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
        ) {
            val horizontalSpacing = NuvioTheme.spacing.md
            val verticalSpacing = NuvioTheme.spacing.md
            val availableWidth = maxWidth
            val columns = run {
                val cols = (availableWidth + horizontalSpacing) /
                    (posterCardStyle.width + horizontalSpacing)
                cols.toInt().coerceAtLeast(1)
            }
            val labelHeight = if (showPosterLabels) {
                androidx.tv.material3.MaterialTheme.typography.titleMedium.lineHeight.value.dp +
                    NuvioTheme.spacing.sm
            } else {
                0.dp
            }
            val itemCount = catalogRow.items.size + if (showSeeAll) 1 else 0
            val rowCount = ceil(itemCount.toDouble() / columns).toInt().coerceAtLeast(1)
            val cellHeight = posterCardStyle.height + labelHeight
            val gridHeight = cellHeight * rowCount + verticalSpacing * (rowCount - 1)

            val entryTargetIndex = if (lastFocusedItemIndex >= 0) lastFocusedItemIndex else 0

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .focusGroup()
                    .dpadRepeatThrottle()
                    .focusRestorer {
                        if (!interactive) {
                            FocusRequester.Default
                        } else {
                            val idx = if (lastFocusedItemIndex >= 0) lastFocusedItemIndex else restorerFocusedIndex
                            val validIdx = idx.coerceIn(0, (catalogRow.items.size - 1).coerceAtLeast(0))
                            catalogRow.items.getOrNull(validIdx)
                                ?.let { itemFocusRequestersByKey.getOrPut(rowItemFocusKey(validIdx, it)) { FocusRequester() } }
                                ?: FocusRequester.Default
                        }
                    },
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                gridItemsIndexed(
                    items = catalogRow.items,
                    key = { index, item -> rowItemFocusKey(index, item) },
                    contentType = { _, item -> item.apiType }
                ) { index, item ->
                    val isPlaceholder = item.id.startsWith("__placeholder_")
                    val cardFocusRequester = if (interactive) {
                        remember(rowItemFocusKey(index, item)) {
                            itemFocusRequestersByKey.getOrPut(rowItemFocusKey(index, item)) { FocusRequester() }
                        }
                    } else {
                        null
                    }
                    val isEntryTarget = interactive && entryFocusRequester != null &&
                        index == entryTargetIndex &&
                        !isPlaceholder
                    val isNonFirstPlaceholder = isPlaceholder && index > 0

                    GridContentCard(
                        item = item,
                        onClick = {
                            if (!isPlaceholder) latestOnItemClick(item.id, item.apiType, catalogRow.addonBaseUrl)
                        },
                        posterCardStyle = posterCardStyle,
                        showLabel = showPosterLabels,
                        isWatched = latestIsItemWatched(item),
                        focusRequester = cardFocusRequester,
                        onLongPress = {
                            if (!isPlaceholder) latestOnItemLongPress(item, catalogRow.addonBaseUrl)
                        },
                        onFocused = {
                            if (interactive && !isPlaceholder) {
                                if (lastFocusedItemIndex != index) {
                                    lastFocusedItemIndex = index
                                    latestOnItemFocused(index)
                                }
                            }
                        },
                        modifier = Modifier
                            .then(
                                if (isEntryTarget) Modifier.focusRequester(entryFocusRequester!!) else Modifier
                            )
                            .then(
                                if (isNonFirstPlaceholder || !interactive) {
                                    Modifier.focusProperties { canFocus = false }
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                if (showSeeAll) {
                    item(key = "${catalogRow.type}_${catalogRow.catalogId}_see_all") {
                        val seeAllCardShapeObj = RoundedCornerShape(posterCardStyle.cornerRadius)
                        val cardDepthStyle = LocalCardDepthStyle.current
                        Column(modifier = Modifier.width(posterCardStyle.width)) {
                            Card(
                                onClick = {
                                    if (interactive) latestOnSeeAll()
                                },
                                modifier = Modifier
                                    .width(posterCardStyle.width)
                                    .height(posterCardStyle.height),
                                shape = CardDefaults.shape(shape = seeAllCardShapeObj),
                                colors = CardDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    focusedContainerColor = NuvioTheme.colors.BackgroundCard
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioTheme.colors.FocusRing),
                                        shape = seeAllCardShapeObj
                                    )
                                ),
                                scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(seeAllCardShapeObj)
                                        .nuvioCardDepth(
                                            shape = seeAllCardShapeObj,
                                            surface = CardDepthSurface.POSTERS,
                                            style = cardDepthStyle
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = seeAllLabel ?: stringResource(R.string.action_see_all),
                                            modifier = Modifier.size(NuvioTheme.spacing.xxl),
                                            tint = NuvioTheme.colors.TextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                                        Text(
                                            text = seeAllLabel ?: stringResource(R.string.action_see_all),
                                            style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                                            color = NuvioTheme.colors.TextSecondary
                                        )
                                    }
                                }
                            }
                            if (showPosterLabels) {
                                Spacer(
                                    modifier = Modifier
                                        .width(posterCardStyle.width)
                                        .padding(top = NuvioTheme.spacing.sm)
                                        .height(androidx.tv.material3.MaterialTheme.typography.titleMedium.lineHeight.value.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentSearchesSection(
    recentSearches: List<String>,
    onSearchSelected: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSectionFocusChanged: (Boolean) -> Unit,
    clearHistoryFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { state ->
                onSectionFocusChanged(state.hasFocus || state.isFocused)
            },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_recent_title),
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Button(
                onClick = onClearHistory,
                modifier = Modifier.focusRequester(clearHistoryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(text = stringResource(R.string.search_recent_clear))
            }
        }

        recentSearches.forEach { recentQuery ->
            Button(
                onClick = { onSearchSelected(recentQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { keyEvent ->
                        val clearHistoryKey = RtlKeyUtils.getClearHistoryDpadKey(isRtl)
                        if (keyEvent.nativeKeyEvent.keyCode == clearHistoryKey) {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                runCatching { clearHistoryFocusRequester.requestFocus() }
                            }
                            true
                        } else {
                            false
                        }
                    },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
            ) {
                Text(
                    text = recentQuery,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SearchInputField(
    query: String,
    canMoveToResults: Boolean,
    voiceFocusRequester: FocusRequester?,
    searchFocusRequester: FocusRequester,
    onSearchFieldFocusChanged: (Boolean) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    showVoiceSearch: Boolean,
    isVoiceListening: Boolean,
    voiceRmsLevel: Float,
    onVoiceSearch: () -> Unit,
    onMoveToResults: () -> Unit,
    onMoveToKeyboard: (() -> Unit)?,
    onOpenDiscover: () -> Unit,
    showDiscoverButton: Boolean,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    clearHistoryFocusRequester: FocusRequester?,
    isScreenActive: Boolean = true,
    horizontalPadding: Dp = NuvioTheme.spacing.xxxl
) {
    var isDiscoverButtonFocused by remember { mutableStateOf(false) }
    var isVoiceButtonFocused by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDiscoverButton) {
            IconButton(
                onClick = onOpenDiscover,
                modifier = Modifier
                    .onFocusChanged { isDiscoverButtonFocused = it.isFocused }
                    .size(NuvioTheme.spacing.huge)
                    .border(
                        width = if (isDiscoverButtonFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isDiscoverButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = stringResource(R.string.cd_open_discover),
                    tint = NuvioTheme.colors.TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        }

        if (showVoiceSearch) {
            val themeAccent = NuvioTheme.colors.Secondary

            // Pulsating animation (constant rhythm while listening)
            val pulseTransition = rememberInfiniteTransition(label = "voicePulse")
            val pulseScale by pulseTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseScale"
            )
            val pulseAlpha by pulseTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulseAlpha"
            )

            // RMS-based ring — smoothly follows mic input level
            val animatedRms by animateFloatAsState(
                targetValue = if (isVoiceListening) voiceRmsLevel else 0f,
                animationSpec = tween(100),
                label = "rmsRing"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(72.dp) // extra room for rings
            ) {
                // Layer 1: Pulsating ring (constant rhythm)
                if (isVoiceListening) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val radius = (size.minDimension / 2f) * pulseScale
                        drawCircle(
                            color = themeAccent.copy(alpha = pulseAlpha * 0.4f),
                            radius = radius
                        )
                    }
                }

                // Layer 2: RMS level ring (voice-reactive)
                if (isVoiceListening && animatedRms > 0.01f) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val rmsRadius = (size.minDimension / 2f) * (1f + animatedRms * 0.35f)
                        drawCircle(
                            color = themeAccent.copy(alpha = 0.25f + animatedRms * 0.25f),
                            radius = rmsRadius,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 2.5f + animatedRms * 3f
                            )
                        )
                    }
                }

                // Layer 3: Actual button
                IconButton(
                    onClick = onVoiceSearch,
                    modifier = Modifier
                        .then(
                            if (voiceFocusRequester != null) {
                                Modifier.focusRequester(voiceFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                        .onFocusChanged { isVoiceButtonFocused = it.isFocused }
                        .size(NuvioTheme.spacing.huge)
                        .border(
                            width = if (isVoiceButtonFocused || isVoiceListening) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                            color = if (isVoiceListening) themeAccent else if (isVoiceButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                        .background(
                            color = if (isVoiceListening) themeAccent.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.cd_voice_search),
                        tint = if (isVoiceListening) themeAccent else NuvioTheme.colors.TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .weight(1f)
                .focusRequester(searchFocusRequester)
                .focusProperties {
                    canFocus = isScreenActive
                }
                .onFocusChanged { focusState ->
                    onSearchFieldFocusChanged(focusState.isFocused)
                }
                .onPreviewKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                onSubmit()
                            }
                            return@onPreviewKeyEvent true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (onMoveToKeyboard != null) {
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    onMoveToKeyboard()
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (canMoveToResults) {
                                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    onMoveToResults()
                                }
                                return@onPreviewKeyEvent true
                            }
                        }

                        else -> {
                            val clearHistoryKey = RtlKeyUtils.getClearHistoryDpadKey(isRtl)
                            if (keyEvent.nativeKeyEvent.keyCode == clearHistoryKey) {
                                if (clearHistoryFocusRequester != null) {
                                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        keyboardController?.hide()
                                        runCatching { clearHistoryFocusRequester.requestFocus() }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                    }
                    false
                },
            keyboardOptions = KeyboardOptions.Default.copy(
                 imeAction = ImeAction.Done,
                 autoCorrectEnabled = false
             ),
            keyboardActions = KeyboardActions(
                onDone = {
                    onSubmit()
                    keyboardController?.hide()
                }
            ),
            singleLine = true,
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            placeholder = {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    color = NuvioTheme.colors.TextTertiary
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NuvioTheme.colors.BackgroundCard,
                unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedIndicatorColor = NuvioTheme.colors.FocusRing,
                unfocusedIndicatorColor = NuvioTheme.colors.Border,
                focusedTextColor = NuvioTheme.colors.TextPrimary,
                unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                cursorColor = NuvioTheme.colors.FocusRing
            )
        )

        // Clear button, requested in review. Placed beside the field rather than as a trailing
        // icon so it is reachable with the D-pad, matching the voice button's treatment.
        if (query.isNotEmpty()) {
            var isClearButtonFocused by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            IconButton(
                onClick = { onQueryChanged("") },
                modifier = Modifier
                    .onFocusChanged { isClearButtonFocused = it.isFocused }
                    .size(NuvioTheme.spacing.huge)
                    .border(
                        width = if (isClearButtonFocused) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                        color = if (isClearButtonFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                    .background(
                        color = NuvioTheme.colors.BackgroundCard,
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_clear_search),
                    tint = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }
}
