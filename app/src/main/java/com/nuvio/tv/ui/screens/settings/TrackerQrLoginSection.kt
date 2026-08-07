@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

@Composable
internal fun TrackerQrLoginSection(
    qrLogin: TrackerQrLoginState,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val session = qrLogin.session
    when {
        qrLogin.isStarting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    NuvioTheme.spacing.md,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
                Text(
                    text = stringResource(R.string.tracker_qr_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        session != null -> {
            val qrBitmap = remember(session.url) {
                runCatching { QrCodeGenerator.generate(session.url, 420, margin = 1) }.getOrNull()
            }
            val nowMillis by produceState(
                initialValue = System.currentTimeMillis(),
                key1 = session.expiresAtEpochMs
            ) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000L)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.tracker_qr_scan_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_tracker_qr),
                            modifier = Modifier.size(150.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Text(
                    text = session.userCode,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = session.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                session.expiresAtEpochMs?.let { expiresAt ->
                    Text(
                        text = stringResource(
                            R.string.tracker_qr_expires,
                            formatTrackingDuration((expiresAt - nowMillis).coerceAtLeast(0L))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        NuvioTheme.spacing.sm,
                        Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (qrLogin.isPolling) {
                        LoadingIndicator(modifier = Modifier.size(18.dp))
                    }
                    val status = qrLogin.errorMessage ?: qrLogin.statusMessage
                    if (!status.isNullOrBlank()) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (qrLogin.errorMessage == null) {
                                NuvioTheme.colors.TextSecondary
                            } else {
                                NuvioTheme.colors.Error
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                if (!qrLogin.errorMessage.isNullOrBlank()) {
                    Text(
                        text = qrLogin.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
                    SettingsDialogActionButton(
                        text = stringResource(R.string.tracker_qr_sign_in),
                        onClick = onStart,
                        primary = true,
                        enabled = qrLogin.isConfigured
                    )
                    if (qrLogin.errorMessage != null) {
                        SettingsDialogActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = onRetry
                        )
                    }
                }
            }
        }
    }
}
