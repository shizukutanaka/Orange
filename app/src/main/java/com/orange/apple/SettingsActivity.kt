package com.orange.apple

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings: family number registration.
 *
 * Intentionally minimal. The only user-configurable setting is the 3-slot
 * family number list. No theme picker, no block-strictness slider, no
 * "whitelist exceptions" — those would imply the engine is wrong and needs
 * manual correction. The engine is the product; the settings are just
 * contact info for emergencies.
 *
 * Reached from:
 *  - Widget long-press (future: widget tap shows count, long-press opens settings)
 *  - FamilyCallbackTile onClick when no number is configured
 *  - History screen bottom link
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)

    val slotNumbers = (1..FamilyCallback.MAX_SLOTS).toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF8C42),
                    titleContentColor = Color.White,
                )
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(stringResource(R.string.settings_section_family))

            Text(
                stringResource(R.string.settings_family_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            val focusManager = LocalFocusManager.current

            for (slot in slotNumbers) {
                var fieldValue by remember {
                    mutableStateOf(prefs.getString("family_$slot", "") ?: "")
                }
                var saved by remember { mutableStateOf(false) }
                var saveError by remember { mutableStateOf(false) }
                val focusRequester = remember { FocusRequester() }

                // Extracted to avoid duplicating the save logic between IME Done and Save button.
                val saveSlot = {
                    focusManager.clearFocus()
                    // Use PhoneNumbers.normalize() rather than a manual filter so that
                    // full-width digits (e.g. "０９０…") are folded to ASCII before display.
                    // A manual `filter { isDigit() }` keeps full-width chars (Unicode Nd),
                    // which causes the field to show full-width after save until the next
                    // prefs reload flips it back to ASCII.
                    val cleaned = PhoneNumbers.normalize(fieldValue)
                    if (cleaned.isEmpty()) {
                        FamilyCallback.clearNumber(ctx, slot)
                        fieldValue = ""
                        saved = true
                        saveError = false
                    } else if (FamilyCallback.setNumber(ctx, slot, cleaned)) {
                        fieldValue = cleaned
                        saved = true
                        saveError = false
                    } else {
                        saveError = true
                    }
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.settings_family_slot, slot),
                            fontSize = 13.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = fieldValue,
                                onValueChange = {
                                    // Allow digits (incl. full-width ０-９ via isDigit()),
                                    // ASCII/full-width '+' and '-' so users who paste
                                    // "+81..." or "090-..." in either width don't lose
                                    // the punctuation mid-edit. normalize() folds all
                                    // full-width chars to ASCII on save.
                                    fieldValue = it.filter { c ->
                                        c.isDigit() || c == '+' || c == '-' ||
                                            c == '＋' || c == '－'  // ＋ and －
                                    }
                                    saved = false
                                    saveError = false
                                },
                                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                placeholder = { Text(stringResource(R.string.settings_family_placeholder)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { saveSlot() }),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFF8C42),
                                    cursorColor = Color(0xFFFF8C42),
                                )
                            )
                            Button(
                                onClick = { saveSlot() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF8C42)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.settings_save))
                            }
                        }
                        if (saved) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_saved),
                                fontSize = 12.sp,
                                color = Color(0xFF34C759)
                            )
                        }
                        if (saveError) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_invalid_number),
                                fontSize = 12.sp,
                                color = Color(0xFFFF3B30)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_history))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ctx.startActivity(android.content.Intent(ctx, HistoryActivity::class.java))
                }
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_view_history),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF1C1C1E)
                    )
                    Text("›", fontSize = 18.sp, color = Color.Gray)
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_block))
            Text(
                stringResource(R.string.settings_block_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            var blockValue by remember { mutableStateOf("") }
            var blockSaved by remember { mutableStateOf(false) }
            var blockError by remember { mutableStateOf(false) }
            val blockFocusRequester = remember { FocusRequester() }

            val submitBlock = {
                focusManager.clearFocus()
                if (ManualBlock.block(ctx, blockValue)) {
                    blockValue = ""
                    blockSaved = true
                    blockError = false
                } else {
                    blockSaved = false
                    blockError = true
                }
            }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = blockValue,
                            onValueChange = {
                                blockValue = it.filter { c ->
                                    c.isDigit() || c == '+' || c == '-' ||
                                        c == '＋' || c == '－'
                                }
                                blockSaved = false
                                blockError = false
                            },
                            modifier = Modifier.weight(1f).focusRequester(blockFocusRequester),
                            placeholder = { Text(stringResource(R.string.settings_block_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitBlock() }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF8C42),
                                cursorColor = Color(0xFFFF8C42),
                            )
                        )
                        Button(
                            onClick = { submitBlock() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C42)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.settings_block_button))
                        }
                    }
                    if (blockSaved) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_block_saved),
                            fontSize = 12.sp,
                            color = Color(0xFF34C759)
                        )
                    }
                    if (blockError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_block_invalid),
                            fontSize = 12.sp,
                            color = Color(0xFFFF3B30)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_allowed))
            Text(
                stringResource(R.string.settings_allowed_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            var allowed by remember { mutableStateOf(AllowSuffixStore.listAllowed(prefs)) }
            if (allowed.isEmpty()) {
                Text(
                    stringResource(R.string.settings_allowed_empty),
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            } else {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        allowed.forEachIndexed { index, suffix ->
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.settings_allowed_suffix, suffix),
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFF1C1C1E)
                                )
                                val revokeLabel = stringResource(R.string.settings_allowed_revoke)
                                TextButton(
                                    onClick = {
                                        AllowSuffixStore.revoke(prefs, "****$suffix")
                                        allowed = AllowSuffixStore.listAllowed(prefs)
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "$revokeLabel $suffix"
                                    }
                                ) {
                                    Text(
                                        stringResource(R.string.settings_allowed_revoke),
                                        color = Color(0xFFFF3B30),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            if (index != allowed.lastIndex) {
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.settings_footer),
                fontSize = 11.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_data_version, ProtectionDataVersion.LAST_UPDATED),
                fontSize = 11.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(java.util.Locale.getDefault()),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Gray,
        letterSpacing = 0.8.sp
    )
}
