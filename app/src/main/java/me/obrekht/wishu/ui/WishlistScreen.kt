package me.obrekht.wishu.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.obrekht.wishu.R
import me.obrekht.wishu.data.Wish

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WishlistScreen(onOpenSettings: () -> Unit = {}, viewModel: WishlistViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wishlist_title)) },
                actions = {
                    val busy = uiState.isGenerating || uiState.isGeneratingUnconstrained
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .combinedClickable(
                                enabled = !busy,
                                onClick = { viewModel.generateWishIdea() },
                                onLongClick = { viewModel.generateUnconstrained() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (busy) {
                            LoadingIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer)
                        } else {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = stringResource(R.string.cd_generate_wish),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.imePadding(),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = uiState.inputText,
                        onValueChange = { viewModel.onInputChange(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.hint_add_wish)) },
                        maxLines = 8,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { viewModel.addWish() },
                        enabled = uiState.inputText.text.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cd_add_wish))
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.suggestions.isNotEmpty()) {
            SuggestionsDialog(
                suggestions = uiState.suggestions,
                onAdd = { viewModel.addSuggestions(it) },
                onDismiss = { viewModel.dismissSuggestions() }
            )
        }
        uiState.unconstrainedResult?.let { result ->
            UnconstrainedDialog(
                result = result,
                onDismiss = { viewModel.dismissUnconstrained() }
            )
        }
        if (uiState.wishes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.empty_wishlist),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.wishes, key = { it.id }) { wish ->
                    WishItem(
                        wish = wish,
                        onDelete = { viewModel.deleteWish(wish) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsDialog(
    suggestions: List<String>,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember(suggestions) { mutableStateListOf(*suggestions.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggestions_title)) },
        text = {
            Column {
                suggestions.forEach { suggestion ->
                    val isChecked = suggestion in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isChecked) selected.remove(suggestion) else selected.add(suggestion)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                if (it) selected.add(suggestion) else selected.remove(suggestion)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text(stringResource(R.string.suggestions_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.suggestions_cancel))
            }
        }
    )
}

@Composable
private fun UnconstrainedDialog(
    result: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.comparison_unconstrained)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(result, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.comparison_close))
            }
        }
    )
}

@Composable
private fun WishItem(wish: Wish, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        ListItem(
            headlineContent = { Text(wish.text) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = {
                FilledTonalIconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_delete_wish))
                }
            }
        )
    }
}
