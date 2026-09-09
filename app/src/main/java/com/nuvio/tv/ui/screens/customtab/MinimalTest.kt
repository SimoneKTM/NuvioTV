package com.nuvio.tv.ui.screens.customtab

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalTvMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MinimalTest() {
    var x by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { x = 1 }
    Text("Test")
}