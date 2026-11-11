package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalFocusPrimeRail
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GlassPanel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TOP_BAR_BAND_HEIGHT
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.MODERN_ROW_HEADER_FOCUS_INSET
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.util.dpadRepeatThrottle

private val HERO_HEIGHT = 380.dp
// 64dp: in sync con rowHorizontalPadding della home moderna e rowStartPadding
// dell'anime — meno di così le card finivano sotto la rail laterale e la
// barra sembrava più stretta delle altre tab.
private val SECTION_PADDING_HORIZONTAL = 64.dp

/** The hero rotates through at most this many backdrops. */
private const val HERO_MAX_SLIDES = 10

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarHomeScreen(
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    viewModel: CalendarHomeViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val firstCardFocusRequester = remember { FocusRequester() }
    // Primo poster della prima riga: target esplicito di GIÙ dall'hero (vedi
    // CalendarHeroSection) — senza il target, il focus search di Compose
    // aggancia la card più vicina al centro dell'hero, aka il 4°/5° poster.
    val firstSectionPosterFocusRequester = remember { FocusRequester() }

    // Initial focus lands on the hero once per screen entry. This must NOT
    // live inside CalendarHeroSection: the hero is a LazyColumn item that
    // leaves and re-enters composition while scrolling, and re-running it
    // stole focus from the rows during UP navigation (the list scrolled to
    // reveal a partially visible row, re-composed the hero, and the hero
    // grabbed focus 50ms later, jumping to the top bar).
    // rememberSaveable: se la destinazione viene ricomposta al ritorno dal
    // dettaglio, l'effetto non deve ripartire e rubare il focus alla card
    // che nel frattempo è stata ripristinata.
    var initialFocusApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (initialFocusApplied) return@LaunchedEffect
        while (uiState.sections.isEmpty() && !uiState.hasLoaded) delay(100)
        if (uiState.sections.isEmpty()) return@LaunchedEffect
        delay(50)
        runCatching { firstCardFocusRequester.requestFocusAfterFrames(2) }
        initialFocusApplied = true
    }

    // Error/empty must be reachable: only show the spinner while loading with
    // nothing to display yet (previous logic hid both states forever).
    val showContent = uiState.sections.isNotEmpty()
    val showSpinner = uiState.isLoading && !showContent
    val showError = !showSpinner && !showContent && uiState.error != null
    val showEmpty = !showSpinner && !showError && !showContent && uiState.hasLoaded

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when {
            showSpinner -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            showError -> {
                CalendarMessageState(
                    title = stringResource(R.string.calendar_error),
                    subtitle = uiState.error ?: "",
                    onRetry = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
                    focusRequester = firstCardFocusRequester
                )
            }
            showEmpty -> {
                CalendarMessageState(
                    title = stringResource(R.string.calendar_empty_title),
                    subtitle = stringResource(R.string.calendar_empty_subtitle),
                    onRetry = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
                    focusRequester = firstCardFocusRequester
                )
            }
            else -> {
                val firstSection = uiState.sections.firstOrNull()

                val rowsListState = rememberLazyListState()
                val density = LocalDensity.current
                val focusScope = rememberCoroutineScope()
                val focusManager = LocalFocusManager.current
                // Ogni riga mantiene il poster attivo quando si passa alla
                // sezione sopra/sotto; il focus non salta più all'inizio.
                val sectionPosterRequesters = remember(
                    uiState.sections.map { section -> section.label to section.items.size }
                ) {
                    uiState.sections.mapIndexed { sectionIndex, section ->
                        section.label to List(section.items.size.coerceAtLeast(1)) { itemIndex ->
                            if (sectionIndex == 0 && itemIndex == 0) firstSectionPosterFocusRequester
                            else FocusRequester()
                        }
                    }.toMap()
                }
                val focusedPosterIndexes = remember { mutableStateMapOf<Int, Int>() }
                // rememberSaveable: la destinazione può essere smontata
                // mentre il dettaglio è in primo piano. Senza, al ritorno
                // focusedSectionIndex ripartiva da -1 e SU/GIÙ sceglievano
                // la sezione sbagliata.
                var focusedSectionIndex by rememberSaveable { mutableIntStateOf(-1) }
                // Sezione da ripristinare al ritorno dal dettaglio. Viene
                // fotografata in ON_PAUSE, prima che il focus del dettaglio
                // possa alterare focusedSectionIndex, e sopravvive alla
                // ricomposizione della destinazione.
                var pendingRestoreSectionIndex by rememberSaveable {
                    mutableIntStateOf(Int.MIN_VALUE)
                }
                var pendingRestorePosterIndex by rememberSaveable { mutableIntStateOf(0) }
                // Evita che il focus transitorio sull'hero durante il ritorno
                // azzeri la sezione che stiamo per ripristinare.
                var restoringFocus by remember { mutableStateOf(false) }
                // Un solo movimento verticale alla volta: con i repeat del
                // D-pad ogni pressione lanciava uno scroll + una richiesta di
                // focus in parallelo, e il focus finiva su sezioni sbagliate
                // o rimbalzava tra due righe. Le pressioni che arrivano
                // mentre il movimento è in corso vengono assorbite, e
                // l'indice si aggiorna subito (non aspetta il callback di
                // focus della card).
                var sectionMovePending by remember { mutableStateOf(false) }
                val moveToSectionPoster: (Int, Int) -> Boolean = { targetIndex, requestedIndex ->
                    val label = uiState.sections.getOrNull(targetIndex)?.label
                    val requesters = label?.let { sectionPosterRequesters[it] }
                    val posterIndex = requesters?.let { requestedIndex.coerceIn(0, it.lastIndex) } ?: 0
                    val requester = requesters?.getOrNull(posterIndex)
                    if (requester == null) {
                        false
                    } else if (sectionMovePending) {
                        true
                    } else {
                        val previous = focusedSectionIndex
                        sectionMovePending = true
                        focusedSectionIndex = targetIndex
                        focusScope.launch {
                            // Compone la sezione di destinazione prima di
                            // chiedere il focus (le righe fuori viewport non
                            // sono altrimenti agganciabili).
                            runCatching { rowsListState.scrollToItem(targetIndex + 1) }
                            val moved = runCatching {
                                requester.requestFocusAfterFrames(2)
                            }.getOrDefault(false)
                            if (!moved) focusedSectionIndex = previous
                            sectionMovePending = false
                        }
                        true
                    }
                }
                // SU dalla prima sezione: il focus torna alla hero, che è
                // l'elemento sopra la lista. Va riportata in composizione con
                // scrollToItem(0), altrimenti dopo aver scrollato in basso la
                // richiesta di focus cade nel vuoto.
                val moveToHero: () -> Boolean = {
                    if (sectionMovePending) {
                        true
                    } else {
                        sectionMovePending = true
                        focusScope.launch {
                            runCatching { rowsListState.scrollToItem(0) }
                            val moved = runCatching {
                                firstCardFocusRequester.requestFocusAfterFrames(2)
                            }.getOrDefault(false)
                            if (moved) {
                                focusedSectionIndex = -1
                            }
                            sectionMovePending = false
                        }
                        true
                    }
                }
                // La sezione di partenza è quella della riga che riceve il
                // tasto (passata da CalendarSection), non uno stato globale
                // aggiornato dai callback di focus: dopo uno scroll o un
                // ritorno dal dettaglio quello stato poteva essere indietro e
                // invertire/bloccare SU e GIÙ.
                val moveVertically: (Int, Int, Int) -> Boolean = { fromSection, delta, fromPoster ->
                    val target = fromSection + delta
                    when {
                        target < 0 -> moveToHero()
                        target > uiState.sections.lastIndex -> true
                        else -> moveToSectionPoster(target, fromPoster)
                    }
                }
                // Al ritorno dal dettaglio il focus deve tornare sulla sezione
                // di partenza, sempre sul PRIMO poster. Navigation a volte
                // ripristina solo il nodo (bordo spento) o l'hero, quindi in
                // ON_RESUME forziamo clear + richiesta esplicita sul primo
                // poster della sezione fotografata in ON_PAUSE.
                val latestSections = rememberUpdatedState(uiState.sections)
                val latestPosterRequesters = rememberUpdatedState(sectionPosterRequesters)
                val latestFocusedPosterIndexes = rememberUpdatedState(focusedPosterIndexes)
                val latestFocusedSectionIndex = rememberUpdatedState(focusedSectionIndex)
                val latestInitialFocusApplied = rememberUpdatedState(initialFocusApplied)
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                if (latestInitialFocusApplied.value) {
                                    pendingRestoreSectionIndex =
                                        latestFocusedSectionIndex.value
                                    pendingRestorePosterIndex = latestFocusedPosterIndexes.value[
                                        latestFocusedSectionIndex.value
                                    ] ?: 0
                                }
                            }

                            Lifecycle.Event.ON_RESUME -> {
                                val targetIndex = pendingRestoreSectionIndex
                                if (targetIndex == Int.MIN_VALUE) {
                                    return@LifecycleEventObserver
                                }
                                pendingRestoreSectionIndex = Int.MIN_VALUE
                                restoringFocus = true
                                focusScope.launch {
                                    // Lascia al NavHost il tempo di ricomporre
                                    // la destinazione, poi riaggancia la card.
                                    focusManager.clearFocus(force = true)
                                    val targetLabel = latestSections.value
                                        .getOrNull(targetIndex)
                                        ?.label
                                    val requesters = targetLabel?.let { latestPosterRequesters.value[it] }
                                    val restoreIndex = requesters?.let {
                                        pendingRestorePosterIndex.coerceIn(0, it.lastIndex)
                                    } ?: 0
                                    val requester = requesters?.getOrNull(restoreIndex)
                                    if (requester != null) {
                                        runCatching {
                                            rowsListState.scrollToItem(targetIndex + 1)
                                        }
                                        requester.requestFocusAfterFrames(3)
                                    } else {
                                        firstCardFocusRequester
                                            .requestFocusAfterFrames(3)
                                    }
                                    restoringFocus = false
                                }
                            }

                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                // Stesso spec verticale della Home/Anime: il focus resta agganciato
                // sotto la banda della barra e il poster in testa non si ferma mai
                // tagliato a metà quando si risale con SU.
                val verticalRowBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, rowsListState) {
                    val topInsetPx = with(density) {
                        (TOP_BAR_BAND_HEIGHT + MODERN_ROW_HEADER_FOCUS_INSET).toPx()
                    }
                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                    object : BringIntoViewSpec {
                        override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                            val currentLeadingEdge = offset
                            if (abs(currentLeadingEdge - topInsetPx) < 1f) return 0f
                            val distance = currentLeadingEdge - topInsetPx
                            if (distance < 0f && !rowsListState.canScrollBackward) return 0f
                            return distance
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
                LazyColumn(
                    state = rowsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .focusRequester(LocalContentFocusRequester.current)
                        .focusRestorer()
                        .dpadRepeatThrottle(scrollableState = rowsListState),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 200.dp)
                ) {
                    if (firstSection != null) {
                        item(key = "hero_header") {
                            CalendarHeroSection(
                                section = firstSection,
                                onNavigateToDetail = onNavigateToDetail,
                                initialFocusRequester = firstCardFocusRequester,
                                downFocusRequester = firstSectionPosterFocusRequester,
                                onHeroFocused = {
                                    if (!restoringFocus) {
                                        focusedSectionIndex = -1
                                    }
                                }
                            )
                        }
                    }

                    itemsIndexed(
                        items = uiState.sections,
                        key = { _, section -> section.label }
                    ) { index, section ->
                        CalendarSection(
                            section = section,
                            onNavigateToDetail = onNavigateToDetail,
                            posterFocusRequesters = sectionPosterRequesters[section.label].orEmpty(),
                            sectionIndex = index,
                            onPosterFocused = { sectionIndex ->
                                focusedSectionIndex = sectionIndex
                            },
                            onPosterIndexFocused = { sectionIndex, posterIndex ->
                                focusedPosterIndexes[sectionIndex] = posterIndex
                            },
                            onVerticalMove = { delta, posterIndex ->
                                moveVertically(index, delta, posterIndex)
                            }
                        )
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarMessageState(
    title: String,
    subtitle: String,
    onRetry: () -> Unit,
    focusRequester: FocusRequester
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            TvCard(
                onClick = onRetry,
                modifier = Modifier.focusRequester(focusRequester),
                shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.Secondary
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            LaunchedEffect(Unit) {
                delay(50)
                runCatching { focusRequester.requestFocusAfterFrames(2) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CalendarHeroSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    initialFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onHeroFocused: () -> Unit = {}
) {
    val firstItem = section.items.firstOrNull() ?: return
    var displayedIndex by rememberSaveable(section.label) { mutableIntStateOf(0) }
    var isHeroFocused by remember { mutableStateOf(false) }
    // Only titles with logo + backdrop + synopsis rotate in the hero; the
    // rest stay exclusively in the poster cards below.
    val heroItems = section.items
    val heroCandidateIndexes = remember(section) {
        heroItems.indices.filter { index ->
            val item = heroItems[index]
            val landscape = item.meta.background ?: item.meta.landscapePoster
            item.meta.logo != null &&
                !landscape.isNullOrBlank() &&
                !item.meta.description.isNullOrBlank()
        }.take(HERO_MAX_SLIDES)
    }
    LaunchedEffect(section.label, heroItems.size, heroCandidateIndexes.size) {
        if (displayedIndex !in heroItems.indices) {
            displayedIndex = 0
        }
        if (displayedIndex !in heroCandidateIndexes && heroCandidateIndexes.isNotEmpty()) {
            displayedIndex = heroCandidateIndexes.first()
        }
    }
    val displayedItem = heroItems.getOrNull(displayedIndex) ?: firstItem

    // Keep Detail's hero backdrop in sync with the calendar hero so back/nav
    // does not inherit a stale Home backdrop.
    val heroImageUrl = displayedItem.meta.background ?: displayedItem.meta.landscapePoster
    LaunchedEffect(heroImageUrl) {
        HeroBackdropState.update(heroImageUrl)
    }

    // La hero si muove solo quando è lei a ricevere il focus; mentre si
    // navigano i poster resta ferma e non distrae né ruba stabilità visiva.
    LaunchedEffect(heroCandidateIndexes, isHeroFocused) {
        if (!isHeroFocused) return@LaunchedEffect
        if (heroCandidateIndexes.size <= 1) return@LaunchedEffect
        while (true) {
            delay(10000L)
            val next = heroCandidateIndexes.firstOrNull { it > displayedIndex }
                ?: heroCandidateIndexes.first()
            displayedIndex = next
        }
    }

    // Initial focus lands on the hero itself (top of the list): on compact or
    // zoomed TV viewports, focusing a poster below triggered a bring-into-view
    // scroll that pushed the whole hero off-screen (Fire Stick).
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HERO_HEIGHT)
                .padding(horizontal = SECTION_PADDING_HORIZONTAL)
                .clip(RoundedCornerShape(20.dp))
                .then(
                    if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester)
                    else Modifier
                )
                .focusable()
                .onFocusChanged {
                    isHeroFocused = it.isFocused
                    if (it.isFocused) {
                        onHeroFocused()
                        HeroBackdropState.update(heroImageUrl)
                    }
                }
                .onPreviewKeyEvent { event ->
                    // GIÙ dall'hero: apri la riga sul PRIMO poster. Il focus
                    // search di Compose sceglie invece la card più vicina al
                    // centro dell'hero (il 4°/5° poster della riga) e da lì la
                    // colonna non si riprende più.
                    if (event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionDown &&
                        downFocusRequester != null
                    ) {
                        val moved = runCatching {
                            downFocusRequester.requestFocus()
                        }.getOrDefault(false)
                        // Se il poster non è ancora composto/agganciabile si
                        // lascia il comportamento di default.
                        return@onPreviewKeyEvent moved
                    }
                    if (event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter)
                    ) {
                        onNavigateToDetail(
                            displayedItem.meta.id,
                            displayedItem.meta.apiType,
                            displayedItem.meta.sourceAddonBaseUrl?.takeIf { it.isNotBlank() }
                        )
                        true
                    } else {
                        false
                    }
                }
        ) {
            if (heroImageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(heroImageUrl)
                        // Richiedi il backdrop a risoluzione TV: senza una
                        // size esplicita Coil riusava la variante da poster
                        // nelle anteprime di alcuni provider.
                        .size(1280, 720)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = displayedItem.meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                    NuvioTheme.colors.Background
                                )
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Transparent,
                                NuvioTheme.colors.Background.copy(alpha = 0.6f),
                                NuvioTheme.colors.Background
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NuvioTheme.colors.Background.copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
            )

            GlassPanel(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
                    .fillMaxWidth(0.55f)
            ) {
                displayedItem.meta.logo?.let { logoUrl ->
                    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
                    if (!logoLoadFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(logoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = displayedItem.meta.name,
                            onError = { logoLoadFailed = true },
                            modifier = Modifier
                                .height(80.dp)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = displayedItem.meta.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } ?: run {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayedItem.meta.name,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SectionBadge(count = section.items.size)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val focusedTypeLabel = localizedContentType(displayedItem.meta.rawType)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    displayedItem.meta.imdbRating?.let { rating ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                        ) {
                            Text(
                                text = "\u2605",
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.colors.Secondary
                            )
                            val ratingText = remember(rating) { String.format(Locale.US, "%.1f", rating) }
                            Text(
                                text = ratingText,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    val releaseYear = remember(displayedItem.meta.releaseInfo) {
                        displayedItem.meta.releaseInfo?.let { releaseInfo ->
                            releaseInfo.split("-").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }
                    releaseYear?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Text(
                        text = focusedTypeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                val episodeChip = displayedItem.episodeLabel?.takeIf { it.isNotBlank() }
                if (episodeChip != null || displayedItem.meta.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        episodeChip?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                            )
                        }
                        displayedItem.meta.genres.take(3).forEach { genre ->
                            Text(
                                text = localizedGenreLabel(genre),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                            )
                        }
                    }
                }

                val synopsis = displayedItem.meta.description
                    ?.takeIf { it.isNotBlank() && it != displayedItem.episodeLabel }
                synopsis?.let { desc ->
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Progress dots: one per hero slide, animated on each rotation.
            if (heroCandidateIndexes.isNotEmpty()) {
                val activeDot = heroCandidateIndexes
                    .indexOf(displayedIndex)
                    .coerceAtLeast(0)
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    heroCandidateIndexes.forEachIndexed { dotIndex, _ ->
                        val isActive = dotIndex == activeDot
                        val dotWidth by animateDpAsState(
                            targetValue = if (isActive) 14.dp else 6.dp,
                            label = "heroDotWidth"
                        )
                        val dotAlpha by animateFloatAsState(
                            targetValue = if (isActive) 0.95f else 0.4f,
                            label = "heroDotAlpha"
                        )
                        Box(
                            modifier = Modifier
                                .width(dotWidth)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = dotAlpha))
                        )
                    }
                }
            }

            if (isHeroFocused) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(2.dp, NuvioTheme.colors.FocusRing, RoundedCornerShape(20.dp))
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CalendarSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    posterFocusRequesters: List<FocusRequester> = emptyList(),
    sectionIndex: Int = 0,
    onPosterFocused: (Int) -> Unit = {},
    onPosterIndexFocused: (Int, Int) -> Unit = { _, _ -> },
    onVerticalMove: (Int, Int) -> Boolean = { _, _ -> false }
) {
    val rowState = rememberLazyListState()
    // Indice della card focalizzata nella riga: serve a fermare SINISTRA
    // sulla prima card e a consegnare il focus al rail, mai alla barra alta.
    var focusedItemIndex by remember(section.label) { mutableIntStateOf(0) }
    val focusPrimeRail = LocalFocusPrimeRail.current
    // Chi ha bisogno di agganciare il primo poster (GIÙ dall'hero o ritorno
    // dal dettaglio) passa il proprio requester: quello interno resta il
    // target di focusRestorer.
    val ownFirstPosterRequester = remember(section.label) { FocusRequester() }
    val firstPosterRequester = posterFocusRequesters.firstOrNull() ?: ownFirstPosterRequester

    // Spec orizzontale della riga: la LazyRow vive dentro lo spec verticale
    // della lista, che altrimenti interpreterebbe le richieste di scorrimento
    // orizzontali come movimenti verticali.
    val density = LocalDensity.current
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, isRtl) {
        val parentStartOffsetPx = with(density) { SECTION_PADDING_HORIZONTAL.roundToPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> =
                defaultBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val childSize = abs(size)
                if (isRtl) {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = containerSize - parentStartOffsetPx.toFloat()
                    val targetForTrailingEdge =
                        if (childSmallerThanParent && initialTarget < childSize) {
                            childSize
                        } else {
                            initialTarget
                        }
                    return (offset + size) - targetForTrailingEdge
                } else {
                    val childSmallerThanParent = childSize <= containerSize
                    val initialTarget = parentStartOffsetPx.toFloat()
                    val spaceAvailable = containerSize - initialTarget

                    val targetForLeadingEdge =
                        if (childSmallerThanParent && spaceAvailable < childSize) {
                            containerSize - childSize
                        } else {
                            initialTarget
                        }

                    return offset - targetForLeadingEdge
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NuvioTheme.colors.Background)
                    .padding(horizontal = SECTION_PADDING_HORIZONTAL, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                SectionBadge(count = section.items.size)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = SECTION_PADDING_HORIZONTAL),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .focusGroup()
                .focusRestorer { firstPosterRequester }
                // SU/GIÙ sono gestiti dalla riga che ha davvero il focus:
                // ogni sezione conosce il proprio indice, quindi il movimento
                // non può più invertirsi o bloccarsi per uno stato globale
                // rimasto indietro dopo uno scroll o il ritorno dal dettaglio.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.key) {
                        Key.DirectionDown -> {
                            onVerticalMove(1, focusedItemIndex)
                            true
                        }

                        Key.DirectionUp -> {
                            onVerticalMove(-1, focusedItemIndex)
                            true
                        }

                        else -> {
                            val backwardKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                            if (event.key == backwardKey && focusedItemIndex <= 0) {
                                focusPrimeRail?.invoke() == true
                            } else {
                                false
                            }
                        }
                    }
                }
        ) {
            itemsIndexed(
                items = section.items,
                key = { _, it -> "${it.meta.id}:${it.releaseDate}:${it.episodeLabel ?: ""}" }
            ) { index, calendarItem ->
                val cardRequester = posterFocusRequesters.getOrNull(index)
                    ?: if (index == 0) firstPosterRequester else null
                CalendarPortraitCard(
                    meta = calendarItem.meta,
                    releaseDate = calendarItem.releaseDate,
                    notInCatalog = calendarItem.notInCatalog,
                    onClick = {
                        onNavigateToDetail(
                            calendarItem.meta.id,
                            calendarItem.meta.apiType,
                            calendarItem.meta.sourceAddonBaseUrl?.takeIf { it.isNotBlank() }
                        )
                    },
                    focusRequester = cardRequester,
                    onFocused = {
                        focusedItemIndex = index
                        onPosterFocused(sectionIndex)
                        onPosterIndexFocused(sectionIndex, index)
                    }
                )
            }
        }
        }
    }
}

@Composable
private fun SectionBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NuvioTheme.colors.Secondary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Secondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarPortraitCard(
    meta: MetaPreview,
    releaseDate: java.time.LocalDate?,
    notInCatalog: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val dateLabel = remember(releaseDate) {
        releaseDate?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())) ?: ""
    }
    val cardShape = RoundedCornerShape(12.dp)
    val cardWidth = 140.dp
    val cardHeight = 210.dp

    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    onFocused?.invoke()
                    HeroBackdropState.update(meta.backdropUrl)
                }
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
        ) {
            if (meta.poster != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(meta.poster)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NuvioTheme.colors.BackgroundCard),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = meta.name.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.3f)
                    )
                }
            }

            if (dateLabel.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            if (notInCatalog) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.calendar_not_in_catalog),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

