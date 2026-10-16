// Create/edit a smart playlist: a name, "match all/any", a list of rules (field · operator ·
// value), a sort order and an optional limit. Results update live once saved.
package dev.yuwixx.resonance.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yuwixx.resonance.data.playlist.SmartField
import dev.yuwixx.resonance.data.playlist.SmartFieldKind
import dev.yuwixx.resonance.data.playlist.SmartOp
import dev.yuwixx.resonance.data.playlist.SmartRule
import dev.yuwixx.resonance.data.playlist.SmartRules
import dev.yuwixx.resonance.data.playlist.SmartSort

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SmartPlaylistEditor(
    initialName: String = "",
    initialRules: SmartRules = SmartRules(rules = listOf(SmartRule(SmartField.PLAY_COUNT, SmartOp.GREATER, "5"))),
    onDismiss: () -> Unit,
    onSave: (name: String, rules: SmartRules) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var matchAll by remember { mutableStateOf(initialRules.matchAll) }
    val rules: SnapshotStateList<SmartRule> = remember { initialRules.rules.toMutableStateList() }
    var sort by remember { mutableStateOf(initialRules.sort) }
    var limitText by remember { mutableStateOf(initialRules.limit?.toString().orEmpty()) }

    val valid = name.isNotBlank() && rules.all { it.field.kind == SmartFieldKind.BOOLEAN || it.value.isNotBlank() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (initialName.isEmpty()) "New smart playlist" else "Edit smart playlist") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel") }
                    },
                    actions = {
                        TextButton(
                            enabled = valid,
                            onClick = {
                                onSave(
                                    name.trim(),
                                    SmartRules(
                                        matchAll = matchAll,
                                        rules = rules.toList(),
                                        sort = sort,
                                        limit = limitText.toIntOrNull()?.takeIf { it > 0 },
                                    ),
                                )
                            },
                        ) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                        ToggleButton(
                            checked = matchAll,
                            onCheckedChange = { matchAll = true },
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Match all") }
                        ToggleButton(
                            checked = !matchAll,
                            onCheckedChange = { matchAll = false },
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            modifier = Modifier.weight(1f),
                        ) { Text("Match any") }
                    }
                }
                itemsIndexed(rules) { index, rule ->
                    RuleRow(
                        rule = rule,
                        onChange = { rules[index] = it },
                        onRemove = { rules.removeAt(index) },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { rules += SmartRule(SmartField.ARTIST, SmartOp.CONTAINS, "") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add rule")
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        EnumDropdown(
                            label = "Sort by",
                            selected = sort,
                            options = SmartSort.entries,
                            optionLabel = { it.label },
                            onSelect = { sort = it },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = limitText,
                            onValueChange = { limitText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Limit") },
                            placeholder = { Text("None") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    OutlinedCard(shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EnumDropdown(
                    label = "Field",
                    selected = rule.field,
                    options = SmartField.entries,
                    optionLabel = { it.label },
                    onSelect = { field ->
                        // Keep the operator if it still makes sense for the new field's kind.
                        val ops = SmartOp.forKind(field.kind)
                        val op = if (rule.op in ops) rule.op else ops.first()
                        val value = when {
                            field.kind == SmartFieldKind.BOOLEAN -> "true"
                            rule.field.kind != field.kind -> ""
                            else -> rule.value
                        }
                        onChange(SmartRule(field, op, value))
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove rule") }
            }
            when (rule.field.kind) {
                SmartFieldKind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rule.value == "true") "is liked" else "is not liked", Modifier.weight(1f))
                    Switch(
                        checked = rule.value == "true",
                        onCheckedChange = { onChange(rule.copy(op = SmartOp.IS, value = it.toString())) },
                    )
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnumDropdown(
                        label = "Condition",
                        selected = rule.op,
                        options = SmartOp.forKind(rule.field.kind),
                        optionLabel = { it.label },
                        onSelect = { onChange(rule.copy(op = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = rule.value,
                        onValueChange = { onChange(rule.copy(value = it)) },
                        label = { Text("Value") },
                        singleLine = true,
                        keyboardOptions = if (rule.field.kind == SmartFieldKind.NUMBER)
                            KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false },
                )
            }
        }
    }
}
