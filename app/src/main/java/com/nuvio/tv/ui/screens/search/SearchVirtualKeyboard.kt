package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.recompositionHighlighter
import android.view.KeyEvent as AndroidKeyEvent

/**
 * Alphabet + digits laid out as the WuPlay-style 6x6 side keyboard.
 * First row a-f, last row 5-9-0, matching the reference app's native keyboard.
 */
private val KEYBOARD_ROWS: List<List<String>> = listOf(
    listOf("a", "b", "c", "d", "e", "f"),
    listOf("g", "h", "i", "j", "k", "l"),
    listOf("m", "n", "o", "p", "q", "r"),
    listOf("s", "t", "u", "v", "w", "x"),
    listOf("y", "z", "1", "2", "3", "4"),
    listOf("5", "6", "7", "8", "9", "0")
)

internal val SearchVirtualKeyboardKeySize = 34.dp
internal val SearchVirtualKeyboardKeyGap = 4.dp

/**
 * On-screen 6x6 side keyboard used while the search field is focused.
 * Every key is D-pad focusable; [firstKeyFocusRequester] targets the top-left key so the
 * search field can move focus down into the keyboard deterministically.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SearchVirtualKeyboard(
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    firstKeyFocusRequester: FocusRequester,
    resultsFocusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onMoveToRecents: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val keys = remember { KEYBOARD_ROWS.flatten() }
    val firstKey = keys.first()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { state ->
                onFocusChanged?.invoke(state.hasFocus || state.isFocused)
            }
            .recompositionHighlighter(),
        verticalArrangement = Arrangement.spacedBy(SearchVirtualKeyboardKeyGap)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(SearchVirtualKeyboardKeyGap),
            verticalArrangement = Arrangement.spacedBy(SearchVirtualKeyboardKeyGap),
            userScrollEnabled = false
        ) {
            items(items = keys, key = { it }) { label ->
                val isLastColumn = KEYBOARD_ROWS.any { it.last() == label }
                KeyCell(
                    label = label,
                    modifier = Modifier
                        .height(SearchVirtualKeyboardKeySize)
                        .then(
                            if (label == firstKey) {
                                Modifier.focusRequester(firstKeyFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (isLastColumn && resultsFocusRequester != null) {
                                Modifier.focusProperties { right = resultsFocusRequester }
                            } else {
                                Modifier
                            }
                        )
                        .onPreviewKeyEvent { event ->
                            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                                event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                                KEYBOARD_ROWS.last().contains(label)
                            ) {
                                onMoveToRecents?.invoke() ?: onEnter()
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    onKey(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(SearchVirtualKeyboardKeyGap))

        // Action row: space + backspace, mirroring the reference layout's bottom strip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN &&
                        event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN
                    ) {
                        onMoveToRecents?.invoke() ?: onEnter()
                        true
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(SearchVirtualKeyboardKeyGap)
        ) {
            KeyCell(
                label = "",
                icon = Icons.Default.SpaceBar,
                modifier = Modifier
                    .weight(1f)
                    .height(SearchVirtualKeyboardKeySize)
            ) {
                onSpace()
            }
            KeyCell(
                label = "",
                icon = Icons.Default.Backspace,
                modifier = Modifier
                    .width(SearchVirtualKeyboardKeySize * 2f + SearchVirtualKeyboardKeyGap)
                    .height(SearchVirtualKeyboardKeySize)
                    .then(
                        if (resultsFocusRequester != null) {
                            Modifier.focusProperties { right = resultsFocusRequester }
                        } else {
                            Modifier
                        }
                    )
            ) {
                onBackspace()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KeyCell(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(
                color = if (isFocused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
            .border(
                width = if (isFocused) 2.dp else NuvioTheme.spacing.hairline,
                color = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
            .focusProperties { canFocus = true }
            .onFocusChanged { state -> isFocused = state.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP &&
                    (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label.ifBlank { "action" },
                tint = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}
