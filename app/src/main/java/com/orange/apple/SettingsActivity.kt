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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings: family contacts, block history link, manual block, allowed numbers.
 *
 * Intentionally minimal in kind, not count: no theme picker, no
 * block-strictness slider, no generic "whitelist exceptions" that would
 * imply the engine is wrong and needs manual correction. Every section here
 * is instead a narrow, purpose-built escape hatch tied to a real failure
 * mode of the engine itself:
 *  - Family contacts (3 slots): one-tap shortcut surfaced on warning notifications
 *  - Block a number (ManualBlock): the user learned of a scam number some
 *    other way and wants it silenced before it ever calls
 *  - Allowed numbers (AllowSuffixStore): undo a false positive whose exact
 *    number isn't on disk (History only stores masked suffixes)
 * The engine is still the product; these are the three narrow places where
 * a human, not the engine, has better information than Orange does.
 *
 * The 2026-11 Ring consolidation added three more sections, all ported
 * from Ring's spam blocker and all in the same spirit (things only the
 * user can know or decide):
 *  - Block by prefix (PrefixBlockStore): a range the user wants silenced
 *  - Check a number (NumberInspector): what Orange thinks of a number,
 *    without waiting for it to call
 *  - Block list transfer (BlockListTransfer): the user's rules in the
 *    schema v1 JSON format Ring used, out via clipboard and back in
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
                    mutableStateOf(prefs.getString("${FamilyCallback.KEY_PREFIX}$slot", "") ?: "")
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
            var blockResult by remember { mutableStateOf<ManualBlock.Result?>(null) }
            val blockFocusRequester = remember { FocusRequester() }

            val submitBlock = {
                focusManager.clearFocus()
                val result = ManualBlock.block(ctx, blockValue)
                blockResult = result
                if (result == ManualBlock.Result.BLOCKED) blockValue = ""
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
                                blockResult = null
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
                    when (blockResult) {
                        ManualBlock.Result.BLOCKED -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_block_saved),
                                fontSize = 12.sp,
                                color = Color(0xFF34C759)
                            )
                        }
                        ManualBlock.Result.ALREADY_TRUSTED -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_block_trusted),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        ManualBlock.Result.INVALID -> {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.settings_block_invalid),
                                fontSize = 12.sp,
                                color = Color(0xFFFF3B30)
                            )
                        }
                        null -> {}
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_prefix))
            Text(
                stringResource(R.string.settings_prefix_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            var prefixValue by remember { mutableStateOf("") }
            var prefixInvalid by remember { mutableStateOf(false) }
            var prefixFull by remember { mutableStateOf(false) }
            var prefixRules by remember { mutableStateOf(PrefixBlockStore.list(prefs)) }
            val prefixFocusRequester = remember { FocusRequester() }

            val submitPrefix = {
                focusManager.clearFocus()
                val rule = canonicalPrefixRule(prefixValue)
                if (rule == null) {
                    prefixInvalid = true
                    prefixFull = false
                } else if (!PrefixBlockStore.add(prefs, rule)) {
                    prefixInvalid = false
                    prefixFull = true
                } else {
                    prefixValue = ""
                    prefixInvalid = false
                    prefixFull = false
                    prefixRules = PrefixBlockStore.list(prefs)
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
                            value = prefixValue,
                            onValueChange = {
                                prefixValue = it.filter { c ->
                                    c.isDigit() || c == '+' || c == '-' ||
                                        c == '＋' || c == '－'
                                }
                                prefixInvalid = false
                                prefixFull = false
                            },
                            modifier = Modifier.weight(1f).focusRequester(prefixFocusRequester),
                            placeholder = { Text(stringResource(R.string.settings_prefix_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitPrefix() }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF8C42),
                                cursorColor = Color(0xFFFF8C42),
                            )
                        )
                        Button(
                            onClick = { submitPrefix() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C42)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.settings_prefix_add))
                        }
                    }
                    if (prefixInvalid) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_prefix_invalid),
                            fontSize = 12.sp,
                            color = Color(0xFFFF3B30)
                        )
                    }
                    if (prefixFull) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_prefix_full),
                            fontSize = 12.sp,
                            color = Color(0xFFFF3B30)
                        )
                    }
                }
            }

            if (prefixRules.isEmpty()) {
                Text(
                    stringResource(R.string.settings_prefix_empty),
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
                        prefixRules.forEachIndexed { index, rule ->
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (rule.scope == PrefixRule.SCOPE_E164)
                                        stringResource(R.string.settings_prefix_international, rule.prefix)
                                    else
                                        stringResource(R.string.settings_prefix_national, rule.prefix),
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFF1C1C1E)
                                )
                                val removeLabel = stringResource(R.string.settings_allowed_revoke)
                                TextButton(
                                    onClick = {
                                        PrefixBlockStore.remove(prefs, rule)
                                        prefixRules = PrefixBlockStore.list(prefs)
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "$removeLabel ${rule.prefix}"
                                    }
                                ) {
                                    Text(
                                        stringResource(R.string.settings_allowed_revoke),
                                        color = Color(0xFFFF3B30),
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            if (index != prefixRules.lastIndex) {
                                HorizontalDivider(color = Color(0xFFF0F0F0))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_lookup))
            Text(
                stringResource(R.string.settings_lookup_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            var lookupValue by remember { mutableStateOf("") }
            var lookupResult by remember { mutableStateOf<NumberInspector.Result?>(null) }
            val lookupFocusRequester = remember { FocusRequester() }

            val submitLookup = {
                focusManager.clearFocus()
                val cleaned = PhoneNumbers.normalize(lookupValue)
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                val iso = (tm?.simCountryIso?.takeIf { it.isNotEmpty() }
                    ?: tm?.networkCountryIso?.takeIf { it.isNotEmpty() })
                    ?.uppercase(java.util.Locale.ROOT)
                val cached = cleaned.isNotEmpty() &&
                    phoneVariants(cleaned, callingCodeOf(iso)).any { SpamCache.contains(prefs, it) }
                lookupResult = NumberInspector.inspect(
                    lookupValue, PrefixBlockStore.listForEngine(prefs), cached, iso
                )
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
                            value = lookupValue,
                            onValueChange = {
                                lookupValue = it.filter { c ->
                                    c.isDigit() || c == '+' || c == '-' || c == ' ' ||
                                        c == '＋' || c == '－' || c == '　'
                                }
                                lookupResult = null
                            },
                            modifier = Modifier.weight(1f).focusRequester(lookupFocusRequester),
                            placeholder = { Text(stringResource(R.string.settings_block_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Phone,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitLookup() }),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF8C42),
                                cursorColor = Color(0xFFFF8C42),
                            )
                        )
                        Button(
                            onClick = { submitLookup() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C42)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.settings_lookup_button))
                        }
                    }
                    val result = lookupResult
                    if (result != null) {
                        Spacer(Modifier.height(8.dp))
                        if (!result.valid) {
                            Text(
                                stringResource(R.string.settings_lookup_invalid),
                                fontSize = 13.sp,
                                color = Color(0xFFFF3B30)
                            )
                        } else {
                            Text(
                                stringResource(
                                    R.string.settings_lookup_number,
                                    result.formatted ?: (result.normalized ?: "")
                                ),
                                fontSize = 14.sp,
                                color = Color(0xFF1C1C1E)
                            )
                            Text(
                                stringResource(
                                    R.string.settings_lookup_type,
                                    stringResource(
                                        when (result.kind) {
                                            NumberInspector.Kind.TOLLFREE -> R.string.settings_lookup_type_tollfree
                                            NumberInspector.Kind.IP_PHONE -> R.string.settings_lookup_type_ip
                                            NumberInspector.Kind.MOBILE -> R.string.settings_lookup_type_mobile
                                            NumberInspector.Kind.NAVI_DIAL -> R.string.settings_lookup_type_navi
                                            NumberInspector.Kind.FIXEDLINE -> R.string.settings_lookup_type_fixedline
                                            NumberInspector.Kind.INTERNATIONAL -> R.string.settings_lookup_type_international
                                            NumberInspector.Kind.OTHER -> R.string.settings_lookup_type_other
                                            NumberInspector.Kind.INVALID -> R.string.settings_lookup_type_other
                                        }
                                    )
                                ),
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Spacer(Modifier.height(4.dp))
                            // Verdict precedence mirrors decide(): spam cache
                            // (Layer 6) before prefix rules (6b) before the
                            // numbering-plan spoof check (Layer 8).
                            val matchedRule = result.matchedPrefixRule
                            when {
                                result.spamCached -> Text(
                                    stringResource(R.string.settings_lookup_spam),
                                    fontSize = 13.sp,
                                    color = Color(0xFFFF3B30)
                                )
                                matchedRule != null -> Text(
                                    stringResource(
                                        R.string.settings_lookup_prefix,
                                        if (matchedRule.scope == PrefixRule.SCOPE_E164)
                                            "+${matchedRule.prefix}"
                                        else matchedRule.prefix
                                    ),
                                    fontSize = 13.sp,
                                    color = Color(0xFFFF3B30)
                                )
                                result.impossibleJp -> Text(
                                    stringResource(R.string.settings_lookup_spoof),
                                    fontSize = 13.sp,
                                    color = Color(0xFFFF3B30)
                                )
                                else -> Text(
                                    stringResource(R.string.settings_lookup_clear),
                                    fontSize = 13.sp,
                                    color = Color(0xFF34C759)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.settings_section_transfer))
            Text(
                stringResource(R.string.settings_transfer_description),
                fontSize = 13.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            val clipboard = LocalClipboardManager.current
            var transferOutcome by remember { mutableStateOf<TransferOutcome?>(null) }

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        BlockListTransfer.exportJson(PrefixBlockStore.listForEngine(prefs))
                                    )
                                )
                                transferOutcome = TransferOutcome(exported = true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C42)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_transfer_export))
                        }
                        Button(
                            onClick = {
                                val text = try {
                                    clipboard.getText()?.text
                                } catch (_: Exception) {
                                    null
                                }
                                transferOutcome = if (text.isNullOrBlank()) {
                                    TransferOutcome(failed = true)
                                } else {
                                    try {
                                        val imported = BlockListTransfer.importJson(text)
                                        var rulesAdded = 0
                                        for (rule in imported.prefixRules) {
                                            val before = PrefixBlockStore.listForEngine(prefs).size
                                            if (PrefixBlockStore.add(prefs, rule) &&
                                                PrefixBlockStore.listForEngine(prefs).size > before
                                            ) rulesAdded++
                                        }
                                        prefixRules = PrefixBlockStore.list(prefs)
                                        var numbersBlocked = 0
                                        for (number in imported.blockExact) {
                                            if (ManualBlock.block(ctx, number) ==
                                                ManualBlock.Result.BLOCKED
                                            ) numbersBlocked++
                                        }
                                        TransferOutcome(
                                            rulesAdded = rulesAdded,
                                            numbersBlocked = numbersBlocked,
                                            skippedAllow = imported.skippedAllow,
                                            skippedReports = imported.skippedReports,
                                        )
                                    } catch (_: IllegalArgumentException) {
                                        TransferOutcome(failed = true)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF8C42)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_transfer_import))
                        }
                    }
                    val outcome = transferOutcome
                    if (outcome != null) {
                        Spacer(Modifier.height(4.dp))
                        when {
                            outcome.failed -> Text(
                                stringResource(R.string.settings_transfer_import_failed),
                                fontSize = 12.sp,
                                color = Color(0xFFFF3B30)
                            )
                            outcome.exported -> Text(
                                stringResource(R.string.settings_transfer_exported),
                                fontSize = 12.sp,
                                color = Color(0xFF34C759)
                            )
                            else -> {
                                Text(
                                    stringResource(
                                        R.string.settings_transfer_import_result,
                                        outcome.rulesAdded, outcome.numbersBlocked
                                    ),
                                    fontSize = 12.sp,
                                    color = Color(0xFF34C759)
                                )
                                if (outcome.skippedAllow > 0 || outcome.skippedReports > 0) {
                                    Text(
                                        stringResource(
                                            R.string.settings_transfer_import_skipped,
                                            outcome.skippedAllow, outcome.skippedReports
                                        ),
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
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

/** Outcome of a block-list transfer action, rendered by the transfer section. */
private data class TransferOutcome(
    val rulesAdded: Int = 0,
    val numbersBlocked: Int = 0,
    val skippedAllow: Int = 0,
    val skippedReports: Int = 0,
    val failed: Boolean = false,
    val exported: Boolean = false,
)

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
