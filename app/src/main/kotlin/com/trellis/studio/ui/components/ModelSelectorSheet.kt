package com.trellis.studio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trellis.studio.data.model.*
import com.trellis.studio.ui.theme.*

/** Bottom sheet for selecting LLM models, grouped by category */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmModelSelector(
    currentModel: String,
    onSelect: (LlmModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(search) {
        if (search.isBlank()) NIM_LLM_GROUPS
        else NIM_LLM_MODELS.filter {
            it.displayName.contains(search, ignoreCase = true) ||
            it.id.contains(search, ignoreCase = true) ||
            it.category.contains(search, ignoreCase = true)
        }.groupBy { it.category }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfDark,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Choose Model", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = TextSecondary)
                }
            }
            // Search
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Search models…", color = TextDisabled) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecondary) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = Purple60,
                    unfocusedBorderColor = BorderDark,
                    focusedContainerColor = CardDark,
                    unfocusedContainerColor = CardDark,
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                filtered.forEach { (category, models) ->
                    item {
                        Text(
                            category,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(models, key = { it.id }) { model ->
                        LlmModelRow(
                            model = model,
                            isSelected = model.id == currentModel,
                            onClick = { onSelect(model); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmModelRow(model: LlmModel, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) CardDark else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                if (model.isVision) ModelBadge("👁 Vision", Purple60)
                if (model.isReasoning) ModelBadge("🧠 Think", Teal)
                if (model.isCode) ModelBadge("</> Code", Amber)
            }
            Text(model.id, style = MaterialTheme.typography.bodySmall, color = TextDisabled, modifier = Modifier.padding(top = 2.dp))
        }
        if (isSelected) Icon(Icons.Default.Check, "Selected", tint = Purple60, modifier = Modifier.size(20.dp))
    }
    HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
}

@Composable
fun ModelBadge(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 5.dp, vertical = 1.dp)
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = color) }
}

/** Bottom sheet for selecting Image Generation models */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageModelSelector(
    currentModelId: String,
    onSelect: (ImageModel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfDark,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.75f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Image Model", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = TextSecondary) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                NIM_IMAGE_GROUPS.forEach { (category, models) ->
                    item {
                        Text(category, style = MaterialTheme.typography.labelMedium, color = TextSecondary,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                    }
                    items(models, key = { it.id }) { model ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(model); onDismiss() }
                                .background(if (model.id == currentModelId) CardDark else Color.Transparent)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.displayName, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                            }
                            if (model.id == currentModelId) Icon(Icons.Default.Check, "Selected", tint = Purple60, modifier = Modifier.size(20.dp))
                        }
                        HorizontalDivider(color = BorderDark.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}
