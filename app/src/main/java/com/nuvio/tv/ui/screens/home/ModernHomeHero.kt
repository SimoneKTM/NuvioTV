package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioMotion

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.computeHeroNextEpisodeText
import com.nuvio.tv.ui.util.recompositionHighlighter
import coil3.request.transitionFactory
import com.nuvio.tv.R
import com.nuvio.tv.core.logo.LogoOverrideStore
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay
import com.nuvio.tv.ui.components.ImdbRatingSourceLabel
import com.nuvio.tv.ui.components.TrailerPlayer
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.util.formatAwards

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroScene(
    state: () -> ModernHeroSceneState,
    isFullScreen: () -> Boolean,
    bgColor: Color,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit
) {
    ModernHeroMediaLayer(
        heroBackdrop = { state().heroBackdrop },
        enrichmentActive = { state().enrichmentActive },
        shouldPlayHeroTrailer = { state().shouldPlayTrailer },
        heroTrailerFirstFrameRendered = { state().trailerFirstFrameRendered },
        heroTrailerUrl = { state().trailerUrl },
        heroTrailerAudioUrl = { state().trailerAudioUrl },
        heroTrailerPlaybackKey = { state().trailerPlaybackKey },
        muted = { state().trailerMuted },
        onTrailerEnded = onTrailerEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        modifier = modifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    ModernHeroGradientLayer(
        bgColor = bgColor,
        isFullScreen = isFullScreen,
        modifier = modifier
    )
}

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: () -> String?,
    enrichmentActive: () -> Boolean,
    shouldPlayHeroTrailer: () -> Boolean,
    heroTrailerFirstFrameRendered: () -> Boolean,
    heroTrailerUrl: () -> String?,
    heroTrailerAudioUrl: () -> String?,
    heroTrailerPlaybackKey: () -> String?,
    muted: () -> Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val shouldPlay by remember { derivedStateOf { shouldPlayHeroTrailer() } }
    val trailerRendered by remember { derivedStateOf { heroTrailerFirstFrameRendered() } }
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlay && trailerRendered) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.slow),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current

    // Backdrop URL is managed upstream (heroSceneStateLambda freezes it
    // during rapid nav / scroll). Only update when enrichment is not active —
    // oppure quando l'utente non sta scrollando: con il gate rigido l'hero
    // restava sul vecchio sfondo per tutta la durata dell'enrichment ("la
    // hero non cambia"). Lo scroll resta protetto (upstream + fast scroll).
    val rawBackdrop by remember { derivedStateOf { heroBackdrop() } }
    val enriching by remember { derivedStateOf { enrichmentActive() } }
    val fastScrolling = LocalFastScrollActive.current.value
    var displayedBackdrop by remember { mutableStateOf(heroBackdrop()) }
    // Adopt a backdrop while enriching only when nothing is displayed yet:
    // on tab re-entry the gate opens at frame 0 (active row not restored) and
    // would otherwise freeze the hero on its initial null (black hero).
    if (rawBackdrop != displayedBackdrop &&
        (!enriching || displayedBackdrop == null || !fastScrolling)
    ) {
        displayedBackdrop = rawBackdrop
    }
    val imageModel = remember(
        localContext,
        displayedBackdrop,
        requestWidthPx,
        requestHeightPx
    ) {
        displayedBackdrop?.let {
            ImageRequest.Builder(localContext)
                .data(it)
                .size(width = requestWidthPx, height = requestHeightPx)
                .build()
        }
    }
    // Keep HeroBackdropState in sync for navigation transitions.
    LaunchedEffect(displayedBackdrop) {
        displayedBackdrop?.let { HeroBackdropState.update(it) }
    }

    Box(modifier = modifier) {
        // Effetto sfondo tipo Netflix: la STESSA immagine decodificata a
        // ~96px e stirata a tutta l'area (ContentScale.FillBounds) riempie i
        // bordi vuoti dietro il crop in alto-destra invece di restare neri.
        // Il decode minuscolo è immediato, quindi la hero appare prima che
        // finisca il caricamento del backdrop grande; sotto Android 12 (niente
        // RenderEffect) la sfocatura resta no-op e vale lo stiramento morbido.
        val fillModel = remember(localContext, displayedBackdrop) {
            displayedBackdrop?.let {
                ImageRequest.Builder(localContext)
                    .data(it)
                    .size(96)
                    .crossfade(false)
                    .build()
            }
        }
        if (fillModel != null) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AsyncImage(
                    model = fillModel,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        // Stirata + sfocata: i bordi fuori dal crop diventano
                        // un diffuso colorato invece di una foto allungata e
                        // pixelata (richiesta "effetto sfocatura").
                        .blur(20.dp)
                        .graphicsLayer { alpha = 1f - transitionProgressState.value },
                    contentScale = ContentScale.FillBounds
                )
            }
        }
        androidx.compose.animation.Crossfade(
            targetState = imageModel,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.overlay),
            label = "heroBackdropCrossfade"
        ) { model ->
            AsyncImage(
                model = model,
                contentDescription = null,
                // Fix 7 (scatti): appena decodificato, la texture viene
                // caricata in anticipo sul render-thread (prepareToDraw)
                // durante la sosta: il primo disegno dello swap non paga
                // più l'upload dentro il frame (200-700ms sul primo
                // ingresso in una riga).
                onSuccess = { state ->
                    runCatching {
                        (state.result.image as? BitmapImage)?.bitmap?.prepareToDraw()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Offscreen serve SOLO durante il crossfade (alpha < 1):
                        // tenerlo sempre fisso costringeva la GPU della Stick a
                        // mantenere un render-target extra per TUTTA la vita
                        // della home. A riposo si torna alla composizione
                        // standard, che è notevolmente più economica.
                        val a = 1f - transitionProgressState.value
                        compositingStrategy = if (a < 1f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                        alpha = a
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopEnd
            )
        }
        if (shouldPlay) {
            val trailerUrlVal = heroTrailerUrl()
            val playbackKeyVal = heroTrailerPlaybackKey()
            val audioUrlVal = heroTrailerAudioUrl()
            val mutedVal = muted()
            key(playbackKeyVal ?: trailerUrlVal) {
                TrailerPlayer(
                    trailerUrl = trailerUrlVal,
                    trailerAudioUrl = audioUrlVal,
                    isPlaying = true,
                    onEnded = onTrailerEnded,
                    onFirstFrameRendered = onFirstFrameRendered,
                    muted = mutedVal,
                    cropToFill = true,
                    overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = transitionProgressState.value
                        }
                )
            }
        }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    isFullScreen: () -> Boolean,
    modifier: Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val fullScreen = isFullScreen()
                val horizontalFadeEndX = size.width * if (fullScreen) 0.65f else 0.45f
                val colorStops = if (fullScreen) {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.90f),
                        0.46f to bgColor.copy(alpha = 0.80f),
                        0.76f to bgColor.copy(alpha = 0.42f),
                        1.0f to Color.Transparent
                    )
                } else {
                    arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    )
                }
                val horizontalGradient = if (isRtl) {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = size.width,
                        endX = size.width - horizontalFadeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colorStops = colorStops,
                        startX = 0f,
                        endX = horizontalFadeEndX
                    )
                }

                // Estendi la dissolvenza verso l'alto: il vecchio tratto
                // finale, concentrato nell'ultimo 18% della scena, appariva
                // come una fascia nera netta sopra le card.
                val bottomStripStartY = size.height * if (fullScreen) 0.58f else 0.70f
                val verticalGradient = Brush.verticalGradient(
                    colorStops = if (fullScreen) {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.30f to bgColor.copy(alpha = 0.35f),
                            0.60f to bgColor.copy(alpha = 0.75f),
                            1.0f to bgColor
                        )
                    } else {
                        arrayOf(
                            0.0f to Color.Transparent,
                            0.42f to bgColor.copy(alpha = 0.18f),
                            0.76f to bgColor.copy(alpha = 0.52f),
                            1.0f to bgColor.copy(alpha = 0.92f)
                        )
                    },
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    // 1. Horizontal fade (reversed in RTL)
                    val rectLeft = if (isRtl) size.width - horizontalFadeEndX else 0f
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(rectLeft, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    previewProvider: () -> HeroPreview?,
    metaPreviewProvider: () -> MetaPreview? = { null },
    enrichmentActive: () -> Boolean = { false },
    portraitMode: Boolean,
    trailerPlaying: () -> Boolean = { false },
    // Id di riserva per l'override del logo: le card "Continua a guardare"
    // non hanno metaPreview (è null), quindi senza questo id l'override
    // veniva ignorato e l'hero mostrava il logo vecchio di TMDB sul percorso
    // CW ("su continua a guardare compare il logo vecchio").
    itemIdFallbackProvider: () -> String? = { null },
    modifier: Modifier = Modifier
) {
    val currentPreview = previewProvider()
    val isEnriching = enrichmentActive()
    
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { Pair(previewProvider(), enrichmentActive()) }.collect { (p, e) ->
            if (!e && p != null) {
                if (stablePreview != p) stablePreview = p
            } else if (e) {
                if (stablePreview != null) stablePreview = null
            }
        }
    }

    val displayPreview = if (!isEnriching && currentPreview != null) currentPreview else stablePreview
    if (displayPreview == null) return

    val nextEpisodeLabel = metaPreviewProvider()?.let { rememberNextEpisodeDateLabel(it) }
    val heroItemId = metaPreviewProvider()?.id ?: itemIdFallbackProvider()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(
            previewProvider = { displayPreview },
            nextEpisodeLabel = nextEpisodeLabel,
            portraitMode = portraitMode,
            trailerPlaying = trailerPlaying,
            itemId = heroItemId
        )
    }
}

@Composable
private fun HeroTitleContent(
    previewProvider: () -> HeroPreview?,
    nextEpisodeLabel: String? = null,
    portraitMode: Boolean,
    trailerPlaying: () -> Boolean = { false },
    itemId: String? = null
) {
    val preview = previewProvider() ?: return
    val highlighterEnabled = LocalRecompositionHighlighterEnabled.current
    // Meno ingrandimento su tutto il blocco: la sinossi entra per intero con
    // più righe a disposizione ("vedo tutto troppo ingrandito", "la
    // descrizione più completa in hero"). Secondo passaggio: ancora meno
    // scala perché in alcuni titoli (titolo lungo + meta + sinossi) il
    // blocco overflowava e veniva tagliato in coda ("non riesco a
    // visualizzare le informazioni").
    val descriptionMaxLines = 6
    val descriptionScale = if (portraitMode) 0.78f else 0.74f
    val titleScale = if (portraitMode) 0.82f else 0.86f
    val metaScale = 0.86f
    val titleSpacing = NuvioTheme.spacing.xs * titleScale
    val metaSpacing = NuvioTheme.spacing.sm * metaScale
    val imdbMetaSpacing = NuvioTheme.spacing.xs * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val labelMedium = MaterialTheme.typography.labelMedium
    val logoMaxWidthPx = remember(density) { with(density) { 256.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 56.dp.roundToPx() } }

    // Logo risolto: prima il logo corrente, poi quello "congelato" della card
    // (il primo valore non vuoto vince): durante lo scroll l'hero non deve mai
    // ricadere sul titolo scritto se esiste un logo già caricato. L'override
    // manuale ("Imposta logo personalizzato") vince su entrambi.
    // collectAsState: la composizione si aggiorna appena l'utente salva.
    // La mappa raccolta DEVE essere letta (remember key): senza lettura non
    // c'è iscrizione al flow e il logo restava quello precedente.
    val logoOverrides by LogoOverrideStore.overrides.collectAsState()
    val effectiveLogoUrl = remember(logoOverrides, itemId, preview.logo, preview.frozenLogoUrl) {
        LogoOverrideStore.resolve(
            itemId,
            preview.logo?.takeIf { it.isNotBlank() }
                ?: preview.frozenLogoUrl?.takeIf { it.isNotBlank() }
        )
    }

    val logoModel = remember(context, effectiveLogoUrl, logoMaxWidthPx, logoHeightPx) {
        effectiveLogoUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val trailerPlayingValue = trailerPlaying()
    val metaAlpha by animateFloatAsState(
        targetValue = if (trailerPlayingValue) 0f else 1f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.slow),
        label = "heroMetaFade"
    )
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }
    // Riga premi con la stessa scala delle meta (metaScale): con
    // labelLarge a size piena la riga "Vinti/N candidature" risultava
    // visibilmente più grande del resto del blocco ("la riga premi
    // stessa [è] ingrandita").
    val heroLabelLarge = MaterialTheme.typography.labelLarge
    val scaledAwardsStyle = remember(heroLabelLarge, metaScale) {
        heroLabelLarge.copy(
            fontSize = heroLabelLarge.fontSize * metaScale,
            lineHeight = heroLabelLarge.lineHeight * metaScale
        )
    }

    Column(
        modifier = (if (highlighterEnabled) Modifier.recompositionHighlighter() else Modifier),
        // NESSUN clipToBounds qui: la sinossi è informazione primaria e deve
        // poter protrarsi di almeno UNA riga oltre il limite del blocco quando
        // righe extra (premi, next-episode) mangiano lo spazio. I chiamanti
        // riservano il buffer (Home: ~23dp liberi fino al titolo della prima
        // riga; Anime: HERO_DESC_RESERVE=40dp sottratti dal bottom padding),
        // quindi l'overflow (sempre <= 1 riga grazie a heightIn(min=1 riga)
        // sulla sinossi) non arriva mai sulle righe sotto. Prima il clip
        // tagliava la riga a metà o la azzerava del tutto: "quando compare
        // 'vinti 3 premi' la descrizione non esce".
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
        // Logo TMDB in fade-in sul primo fotogramma (niente upload texture
        // dentro il frame dello swap dell'hero).
        var logoLoaded by remember(effectiveLogoUrl) { mutableStateOf(false) }
        val showLogo = !effectiveLogoUrl.isNullOrBlank() && !logoLoadFailed
        val logoAlpha by animateFloatAsState(
            targetValue = if (logoLoaded) 1f else 0f,
            animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.overlay),
            label = "heroLogoFade"
        )
        // NESSUN logo testuale automatico: la scritta generata compariva per
        // pochi millisecondi durante lo scroll anche sui titoli che un logo
        // TMDB ce l'hanno (finché l'immagine caricava) e rendeva tutti i
        // titoli identici/standard. Ora l'area resta riservata ma vuota finché
        // il logo non è pronto; se il logo non esiste (o il caricamento fallisce)
        // resta vuota e si usa eventuale logo personalizzato dal menu Azioni.
        if (showLogo) {
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .widthIn(min = 100.dp, max = 256.dp)
                    .fillMaxWidth()
                    .clipToBounds(),
                contentAlignment = Alignment.CenterStart
            ) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = preview.title,
                    onError = { logoLoadFailed = true },
                    // Fix 7: pre-upload della texture anche del logo (stesso
                    // motivo del backdrop: niente upload dentro il frame dello
                    // swap dell'hero).
                    onSuccess = { state ->
                        logoLoaded = true
                        runCatching {
                            (state.result.image as? BitmapImage)?.bitmap?.prepareToDraw()
                        }
                    },
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = logoAlpha },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            }
        } else {
            // Spazio riservato (stessa altezza del logo): il blocco premi/meta
            // sotto non salta quando l'item non ha logo.
            Spacer(modifier = Modifier.height(56.dp))
        }

        val localizedAwards = remember(preview.awards, context) {
            formatAwards(preview.awards, context)
        }
        if (!localizedAwards.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                modifier = Modifier.graphicsLayer { alpha = metaAlpha }
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    tint = NuvioPrimitives.green500,
                    modifier = Modifier.size(18.dp * metaScale)
                )
                Text(
                    text = localizedAwards,
                    style = scaledAwardsStyle,
                    color = NuvioPrimitives.green500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        nextEpisodeLabel?.let { label ->
            if (preview.isSeries && preview.secondaryHighlightText == null) {
                val heroEpisodeText = remember(context, label) {
                    computeHeroNextEpisodeText(context, label)
                }
                Text(
                    text = heroEpisodeText,
                    style = labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = metaAlpha }
                )
            }
        }

        val strStatusEnded = stringResource(if (preview.isSeries) R.string.series_status_ended else R.string.movie_status_ended)
        val strStatusContinuing = stringResource(if (preview.isSeries) R.string.series_status_continuing else R.string.movie_status_continuing)
        val strStatusCurrent = stringResource(if (preview.isSeries) R.string.series_status_current else R.string.movie_status_current)
        val strStatusCancelled = stringResource(if (preview.isSeries) R.string.series_status_cancelled else R.string.movie_status_cancelled)
        val strStatusReleased = stringResource(if (preview.isSeries) R.string.series_status_released else R.string.movie_status_released)
        val strStatusPlanned = stringResource(if (preview.isSeries) R.string.series_status_planned else R.string.movie_status_planned)
        val strStatusRumored = stringResource(if (preview.isSeries) R.string.series_status_rumored else R.string.movie_status_rumored)
        val strStatusInProduction = stringResource(if (preview.isSeries) R.string.series_status_in_production else R.string.movie_status_in_production)
        val strStatusPostProduction = stringResource(if (preview.isSeries) R.string.series_status_post_production else R.string.movie_status_post_production)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase()
                    "continuing", "returning series" -> strStatusContinuing.uppercase()
                    "current" -> strStatusCurrent.uppercase()
                    "cancelled", "canceled" -> strStatusCancelled.uppercase()
                    "released" -> strStatusReleased.uppercase()
                    "planned" -> strStatusPlanned.uppercase()
                    "rumored" -> strStatusRumored.uppercase()
                    "in production" -> strStatusInProduction.uppercase()
                    "post production" -> strStatusPostProduction.uppercase()
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val showImdbInPrimary = !preview.isSeries && !hasSecondaryBadge && !preview.imdbText.isNullOrBlank()
        val showImdbInPrimaryWithHighlight = showImdbInPrimary && secondaryHighlightText == null
        val showImdbInSecondary = !preview.imdbText.isNullOrBlank() &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)

        Row(
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = metaAlpha },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.genres, context) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                        add(com.nuvio.tv.ui.util.localizedGenreLabel(context, genre))
                    }
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val imdbText = preview.imdbText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                showImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!runtimeText.isNullOrBlank() && !yearText.isNullOrBlank()) {
                        HeroMetaDivider(metaScale)
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (showImdbInPrimaryWithHighlight && !imdbText.isNullOrBlank()) {
                        HeroImdbMeta(
                            imdbText = imdbText,
                            textStyle = labelMedium,
                            textColor = NuvioTheme.colors.TextSecondary,
                            logoSize = 30.dp * metaScale,
                            spacing = imdbMetaSpacing
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || showImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = metaAlpha },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (showImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        textStyle = labelMedium,
                        textColor = NuvioTheme.colors.TextSecondary,
                        logoSize = 30.dp * metaScale,
                        spacing = imdbMetaSpacing
                    )
                }
                if (showImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(metaScale)
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
            }
        }

        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            // Lo spazio che resta è misurato qui: le righe che entrano davvero
            // si arrotondano per difetto, così la sinossi non viene mai
            // tagliata a metà riga (richiesta "la descrizione viene tagliata").
            // heightIn(min = 1 riga): se i premi/next-episode consumano tutto
            // lo spazio la sinossi continua comunque per una riga intera nel
            // buffer riservato dai chiamanti invece di sparire ("non compare
            // proprio"). L'overflow è quindi sempre <= 1 riga.
            val descriptionMinHeight = with(density) { scaledDescriptionStyle.lineHeight.toDp() }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = descriptionMinHeight)
                    .graphicsLayer { alpha = metaAlpha }
            ) {
                val lineHeightPx = with(density) { scaledDescriptionStyle.lineHeight.toPx() }
                val linesThatFit = if (lineHeightPx > 0f) {
                    (with(density) { maxHeight.toPx() } / lineHeightPx).toInt()
                } else {
                    descriptionMaxLines
                }
                Text(
                    text = description,
                    style = scaledDescriptionStyle,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = linesThatFit.coerceIn(1, descriptionMaxLines),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        ImdbRatingSourceLabel(
            logoModifier = Modifier.size(logoSize),
            textStyle = textStyle,
            textColor = textColor
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(NuvioTheme.spacing.hairline, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(NuvioTheme.spacing.hairline)
                .height(NuvioTheme.spacing.md)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(NuvioTheme.spacing.hairline, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((NuvioTheme.spacing.xs * scale).coerceAtLeast(NuvioTheme.spacing.xxs))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioTheme.colors.TextTertiary.copy(alpha = 0.78f))
    )
}
