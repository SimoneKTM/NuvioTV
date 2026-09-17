package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.nuvio.tv.ui.theme.NuvioTheme

data class ExtraLogoOption(
    val name: String,
    val icon: ImageVector
)

@Composable
fun ExtraLogoPicker(
    options: List<ExtraLogoOption>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val columns = 5
    val rows = options.chunked(columns)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEachIndexed { colIndex, option ->
                    val index = rowIndex * columns + colIndex
                    var isFocused by remember { mutableIntStateOf(0) }
                    val isSelected = index == selectedIndex
                    val isFocusedState = isFocused > 0
                    val borderColor = when {
                        isSelected -> NuvioTheme.colors.Primary
                        isFocusedState -> NuvioTheme.colors.FocusRing
                        else -> NuvioTheme.colors.Border
                    }
                    val borderWidth = if (isSelected || isFocusedState) 2.dp else 1.dp
                    val bgColor = when {
                        isSelected -> NuvioTheme.colors.Primary.copy(alpha = 0.15f)
                        isFocusedState -> NuvioTheme.colors.FocusRing.copy(alpha = 0.12f)
                        else -> NuvioTheme.colors.Surface
                    }

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bgColor)
                            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
                            .focusable()
                            .onFocusChanged { isFocused = if (it.isFocused) 1 else 0 }
                            .clickable { onOptionSelected(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = option.name,
                            tint = when {
                                isSelected -> NuvioTheme.colors.Primary
                                isFocusedState -> NuvioTheme.colors.FocusRing
                                else -> NuvioTheme.colors.TextSecondary
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
