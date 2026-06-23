package com.orange.apple

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Block history + false-positive recovery.
 *
 * Single-purpose: show what Orange blocked and let the user un-block any
 * entry. No search, no filters, no export — those would imply the history
 * is a feature, not a safety net.
 *
 * Numbers are shown masked (****1234). The "Allow" action stores the
 * 4-digit suffix in AllowSuffixStore so future calls matching that suffix
 * ring through (SilentBlockerService checks AllowSuffixStore before any
 * blocking layer). This differs from TrustNotifier's "Restore" action
 * (RestoreReceiver): Restore has the full number and can remove the exact
 * SpamCache hash; Allow cannot because only the masked suffix is on disk.
 *
 * The UI limits Allow to reasons where suffix-matching is meaningful:
 * SPAM_CACHE, REPEAT_CALLER, WANGIRI_CALLBACK, FOREIGN_GENERIC,
 * FOREIGN_ELEVATED. Structural spoofs (DOMESTIC_SPOOF) are excluded —
 * an impossible number cannot become possible, so Allow would be misleading.
 */
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HistoryScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen() {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences(SilentBlockerService.PREFS, Context.MODE_PRIVATE)
    var entries by remember {
        mutableStateOf(BlockHistoryStore.load(prefs, System.currentTimeMillis()))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                entries = BlockHistoryStore.load(prefs, System.currentTimeMillis())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val lazyListState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF8C42),
                    titleContentColor = Color.White,
                )
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    color = Color.Gray,
                    fontSize = 15.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = lazyListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { ConsultBanner() }
                items(entries, key = { "${it.maskedNumber}_${it.timestampMs}" }) { entry ->
                    HistoryCard(
                        entry = entry,
                        onAllow = {
                            AllowSuffixStore.allow(prefs, entry.maskedNumber)
                            BlockHistoryStore.remove(prefs, entry)
                            // Use value equality (!=) not identity (!==): Entry is a data
                            // class, and Compose may recompose before this lambda fires,
                            // creating a new instance with the same fields but a different
                            // object reference. Identity comparison would then silently fail
                            // to remove the entry from the visible list.
                            entries = entries.filter { it != entry }
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.history_footer),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            ctx.startActivity(
                                Intent(ctx, SettingsActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                        },
                        modifier = Modifier.padding(horizontal = 0.dp)
                    ) {
                        Text(
                            stringResource(R.string.history_open_settings),
                            color = Color(0xFFFF8C42),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsultBanner() {
    val ctx = LocalContext.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.history_consult_banner),
                fontSize = 13.sp,
                color = Color(0xFF795548),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:%239110"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            ) {
                Text(
                    stringResource(R.string.postcall_action_9110),
                    color = Color(0xFFFF8C42),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(entry: BlockHistoryStore.Entry, onAllow: () -> Unit) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val dateStr = fmt.format(Date(entry.timestampMs))
    val reasonStr = entry.reason.toDisplayString(ctx)
    // Only exclude reasons where suffix-Allow is meaningless or misleading:
    //  WITHHELD_NUMBER  — number is "" so AllowSuffixStore.allow() would be a no-op
    //  DOMESTIC_SPOOF   — structurally impossible number; Allow cannot make it possible
    // All other reasons represent a caller the user may legitimately want to unblock
    // (e.g. CARRIER_VERIFICATION_FAILED for a carrier with STIR/SHAKEN issues, or
    //  PREMIUM_RATE_INTERNATIONAL for a family member calling from the Caribbean).
    val canAllow = entry.reason !in setOf(BlockReason.WITHHELD_NUMBER, BlockReason.DOMESTIC_SPOOF)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.maskedNumber,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1C1C1E)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$dateStr  ·  $reasonStr",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                if (entry.reason == BlockReason.DOMESTIC_SPOOF) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.history_spoof_no_allow),
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
                if (entry.reason == BlockReason.FOREIGN_ELEVATED ||
                    entry.reason == BlockReason.FOREIGN_GENERIC) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.history_foreign_tip),
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
            if (canAllow) {
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = onAllow,
                    modifier = Modifier.semantics {
                        contentDescription = "${stringResource(R.string.history_action_allow)} ${entry.maskedNumber}"
                    }
                ) {
                    Text(
                        stringResource(R.string.history_action_allow),
                        color = Color(0xFFFF8C42),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun BlockReason.toDisplayString(ctx: Context): String = when (this) {
    BlockReason.SPAM_CACHE -> ctx.getString(R.string.reason_spam_cache)
    BlockReason.FOREIGN_ELEVATED -> ctx.getString(R.string.reason_foreign_elevated)
    BlockReason.FOREIGN_GENERIC -> ctx.getString(R.string.reason_foreign_generic)
    BlockReason.DOMESTIC_SPOOF -> ctx.getString(R.string.reason_domestic_spoof)
    BlockReason.WANGIRI_CALLBACK -> ctx.getString(R.string.reason_wangiri)
    BlockReason.CARRIER_VERIFICATION_FAILED -> ctx.getString(R.string.reason_stir_shaken)
    BlockReason.WITHHELD_NUMBER -> ctx.getString(R.string.reason_withheld)
    BlockReason.PREMIUM_RATE_INTERNATIONAL -> ctx.getString(R.string.reason_premium_rate)
    BlockReason.DND_HONOR -> ctx.getString(R.string.reason_dnd)
    BlockReason.REPEAT_CALLER -> ctx.getString(R.string.reason_repeat_caller)
}
