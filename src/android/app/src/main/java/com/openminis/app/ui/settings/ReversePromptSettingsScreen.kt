package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.agent.ReversePromptStore
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-reverse-prompt] Settings editor for REVERSE_PROMPT.md — the
 * user-authored operating procedure that is injected into the agent system
 * prompt on every turn.
 *
 * The screen mirrors [SoulSettingsScreen]'s conventions on purpose (same
 * [SettingsScaffold], Save in the app bar, dirty-check against a baseline
 * snapshot, Restore Default behind a confirmation) so a user who has edited
 * the persona once already knows how this page behaves.
 *
 * Two deliberate differences from the Soul screen:
 *  - No hard save gate. Over [ReversePromptStore.CHAR_LIMIT] the writer still
 *    saves and the prompt-build step clips with a visible marker; a procedure
 *    is a working document, and silently refusing to persist a long draft is
 *    worse than clipping it. The counter turns red at the cap as the signal.
 *  - The file path is shown inline. Editing the .md from outside the app (shell,
 *    file browser, a text editor) is a first-class path, so the location must
 *    be discoverable rather than documented only in code comments.
 */
@Composable
fun ReversePromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var body by remember { mutableStateOf("") }
    var baseline by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    // [T-android-reverse-prompt-toast] One-shot confirmation that the write
    // landed. A silent save on a screen whose whole purpose is "did my edit
    // take effect?" reads as a dead button.
    var savedNote by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val current = withContext(Dispatchers.IO) {
            ReversePromptStore.ensureExists(context)
            ReversePromptStore.load(context).orEmpty()
        }
        body = current
        baseline = current
        loaded = true
    }

    val isDirty = loaded && body != baseline
    val overLimit = body.length > ReversePromptStore.CHAR_LIMIT
    val active = body.isNotBlank()

    val attemptBack: () -> Unit = {
        // Nothing is lost by leaving: edits are drafts until Save, and the
        // procedure is injected from disk — an unsaved change never silently
        // alters the prompt. Confirming here would be busywork.
        onBack()
    }

    val save: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { ReversePromptStore.save(context, body) }
            baseline = body
            savedNote = true
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.reverse_prompt_title),
        onBack = attemptBack,
        actions = {
            MinisTextButton(
                onClick = save,
                enabled = isDirty,
            ) { Text(stringResource(R.string.reverse_prompt_save)) }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.reverse_prompt_section_about),
            footer = stringResource(R.string.reverse_prompt_footer),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.reverse_prompt_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                // The on-disk path is the API: anything read here can also be
                // edited by shell / file browser / another app.
                Text(
                    text = stringResource(R.string.reverse_prompt_file_path, "memory/REVERSE_PROMPT.md"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (active) R.string.reverse_prompt_status_on else R.string.reverse_prompt_status_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        body = it
                        savedNote = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text(stringResource(R.string.reverse_prompt_placeholder)) },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.reverse_prompt_count,
                        body.length,
                        ReversePromptStore.CHAR_LIMIT,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overLimit) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (savedNote) {
                    Text(
                        text = stringResource(R.string.reverse_prompt_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (overLimit) {
                Text(
                    text = stringResource(R.string.reverse_prompt_over_limit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            MinisOutlinedButton(
                onClick = { showRestoreDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.reverse_prompt_restore_default)) }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(stringResource(R.string.reverse_prompt_restore_confirm_title)) },
            text = { Text(stringResource(R.string.reverse_prompt_restore_confirm_message)) },
            confirmButton = {
                MinisButton(onClick = {
                    val restored = ReversePromptStore.DEFAULT_CONTENT
                    body = restored
                    baseline = restored
                    scope.launch {
                        withContext(Dispatchers.IO) { ReversePromptStore.save(context, restored) }
                        savedNote = true
                    }
                    showRestoreDialog = false
                }) { Text(stringResource(R.string.reverse_prompt_restore_confirm_action)) }
            },
            dismissButton = {
                MinisOutlinedButton(onClick = { showRestoreDialog = false }) {
                    Text(stringResource(R.string.reverse_prompt_cancel))
                }
            },
        )
    }
}
