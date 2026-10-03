package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** One entry of a [DropdownPicker] menu: display label + stable value key. */
data class DropdownOption(
    val label: String,
    val value: String
)

/**
 * Title-over-value card that opens a D-pad friendly DropdownMenu, seeded with
 * focus on the current selection (TV fix for #2848 / incomplete #2507).
 * Shared by Library filters and the Search sort menu so both behave alike.
 */
@Composable
fun DropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<DropdownOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (DropdownOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    // Seed focused option with the current selection so reopen highlights the right row
    // even before focus lands (fixes #2848 / incomplete #2507 race on TV).
    var focusedOptionValue by remember(expanded) {
        mutableStateOf(if (expanded) selectedValue else null)
    }
    val selectedItemFocusRequester = remember { FocusRequester() }
    val selectedBringIntoViewRequester = remember { BringIntoViewRequester() }

    // Popup content attaches focus targets a few frames after expand. A fixed 50ms
    // delay was flaky on TV and left focus on the first item for non-top selections.
    LaunchedEffect(expanded, selectedValue) {
        if (!expanded || selectedValue == null) return@LaunchedEffect
        var focused = selectedItemFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
        if (!focused) return@LaunchedEffect
        runCatching { selectedBringIntoViewRequester.bringIntoView() }
        // Material DropdownMenu may still move initial focus to the first item after
        // the popup settles; re-assert once so long lists keep the real selection.
        delay(48)
        if (runCatching { selectedItemFocusRequester.requestFocus() }.getOrDefault(false)) {
            runCatching { selectedBringIntoViewRequester.bringIntoView() }
        }
    }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse, title) else stringResource(R.string.cd_expand, title),
                        tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                focusedOptionValue = null
                onExpandedChange(false)
            },
            modifier = Modifier
                .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                .heightIn(max = 320.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val isOptionFocused = option.value == focusedOptionValue
                val itemTextColor = when {
                    isOptionFocused -> NuvioTheme.colors.OnSecondary
                    isSelected -> NuvioTheme.colors.TextPrimary
                    else -> NuvioTheme.colors.TextPrimary
                }
                val itemBackgroundColor = when {
                    isOptionFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .then(
                            if (isSelected) {
                                Modifier
                                    .focusRequester(selectedItemFocusRequester)
                                    .bringIntoViewRequester(selectedBringIntoViewRequester)
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .onFocusChanged { state ->
                            val hasFocus = state.isFocused || state.hasFocus
                            focusedOptionValue = when {
                                hasFocus -> option.value
                                focusedOptionValue == option.value -> null
                                else -> focusedOptionValue
                            }
                        },
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor,
                        disabledTextColor = NuvioTheme.colors.TextDisabled
                    )
                )
            }
        }
    }
}
