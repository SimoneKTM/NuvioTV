package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.catalogRowStableKey
import java.util.Locale

internal const val TOP10_ADDON_ID = "trakt-top10"
internal const val TOP10_CATALOG_ID = "top10-monthly"

/**
 * The exact modern-layout row key for the Top 10 row. Focus keys are built as
 * "<rowKey>::<itemId>", so run time code can recognise a Top 10 key — e.g.
 * the DPAD key swallowing in ModernHomeRowsList must stay off for Top 10
 * cards: they never expand, even though the expanded-state key still drives
 * the hero trailer.
 */
internal val TOP10_MODERN_ROW_KEY =
    catalogRowStableKey(TOP10_ADDON_ID, "", "all", TOP10_CATALOG_ID)

/**
 * Builds the synthetic Home row shown right after Latest Releases with the
 * ten most-watched shows of the month. Items arrive already ranked 1-10 by
 * Trakt, so the modern layout numbers the cards from their list index.
 *
 * The row is typed "all" so localized type suffixes (" - Film"/" - Serie")
 * are skipped, and hasMore is false so pagination never triggers (the classic
 * / grid "See all" card has no addon catalog to route to either).
 */
internal fun buildTop10HomeRow(
    context: Context,
    items: List<MetaPreview>
): HomeRow.Catalog? {
    if (items.isEmpty()) return null
    return HomeRow.Catalog(
        CatalogRow(
            addonId = TOP10_ADDON_ID,
            addonName = "Trakt",
            addonBaseUrl = "",
            catalogId = TOP10_CATALOG_ID,
            catalogName = localizedTop10Title(context),
            type = ContentType.UNKNOWN,
            rawType = "all",
            items = items,
            hasMore = false,
            supportsSkip = false
        )
    )
}

private fun localizedTop10Title(context: Context): String {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return context.getString(R.string.home_row_top10_month)
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(config)
        .getString(R.string.home_row_top10_month)
}
