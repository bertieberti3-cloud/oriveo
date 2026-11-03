package ai.oriveo.community.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import ai.oriveo.community.R
import ai.oriveo.community.core.app.GlobalSnackbarMessage
import ai.oriveo.community.core.app.UiText
import ai.oriveo.community.core.util.COMPOSER_MAX_INPUT_LENGTH
import ai.oriveo.community.core.util.ComposerLengthLimiter

/**
 * A `TextFieldState`-based composer input, ready for `BasicTextField(state = …)`.
 *
 * Why this exists next to [rememberComposerLengthLimitedField]: only the `TextFieldState` override
 * of `BasicTextField` can be a Compose content receiver (`Modifier.contentReceiver`), and that is
 * the only way an Android editor can accept an image from the software keyboard / clipboard
 * (`EditorInfo.contentMimeTypes` + `InputConnection.commitContent`). Without it Samsung's
 * clipboard refuses to paste an image into the chat at all.
 *
 * The owner still holds a plain `String`, exactly like the legacy helper:
 * - user edits (typing, IME, text paste, undo) flow out through [onTextChange];
 * - programmatic writes (draft restore, edit restore, clearing after send) flow in from [text] and
 *   are accepted as they are, without truncation and without a notice;
 * - the length limit, the "limit reached" notice and the "an over-long existing draft may only
 *   shrink" rule are the same [ComposerLengthLimiter] the legacy field uses, applied through
 *   [InputTransformation] so the caret lands where the limiter says.
 *
 * [selectionProbe] keeps working: the settled selection is recorded, and a pending caret left by
 * [ComposerSelectionProbe.insert] (dictation) is applied to the next text adoption.
 */
internal class ComposerTextState(
    val state: TextFieldState,
    val inputTransformation: InputTransformation,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberComposerTextState(
    text: String,
    onTextChange: (String) -> Unit,
    selectionProbe: ComposerSelectionProbe? = null,
    limit: Int = COMPOSER_MAX_INPUT_LENGTH,
): ComposerTextState {
    val snackbar = rememberGlobalSnackbarManager()
    val state = rememberTextFieldState(initialText = text)
    val limiter = remember { ComposerLengthLimiter(text, limit) }
    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentText by rememberUpdatedState(text)
    val currentProbe by rememberUpdatedState(selectionProbe)
    val currentSnackbar by rememberUpdatedState(snackbar)

    /**
     * The owner text this field has already accounted for: either pushed out by a user edit or
     * adopted from an external write. Anything else arriving from the owner is a new programmatic
     * write. Without it the effect would race the echo of its own edits (the owner updates a frame
     * later) and could rewrite the field with the previous keystroke.
     */
    var settledOwnerText by remember { mutableStateOf(text) }

    val notifyLimitReached = {
        val probe = currentProbe
        if (probe != null) {
            probe.onLimitExceeded()
        } else {
            currentSnackbar?.show(
                GlobalSnackbarMessage(
                    message = UiText.Resource(R.string.chat_input_length_limit_reached, listOf(limit)),
                ),
            )
        }
    }

    val inputTransformation = remember(limiter, limit) {
        InputTransformation {
            val incoming = TextFieldValue(
                text = asCharSequence().toString(),
                selection = selection,
                // Rewriting a string an IME is composing interrupts the composition; the limiter
                // passes such edits through untouched and checks them once committed.
                composition = state.composition,
            )
            val edit = limiter.onUserEdit(incoming)
            if (edit.value.text != incoming.text) {
                replace(0, length, edit.value.text)
                selection = edit.value.selection
            }
            if (edit.shouldNotify) notifyLimitReached()
        }
    }

    // Field -> owner. The value the owner already holds is skipped: that is the echo of a
    // programmatic write below, not a user edit.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { value ->
            if (value != currentText) {
                settledOwnerText = value
                currentOnTextChange(value)
            }
        }
    }

    // Owner -> field: draft restore, edit restore, clearing after send, dictation insert.
    LaunchedEffect(state) {
        snapshotFlow { currentText }.collect { value ->
            if (value == settledOwnerText) return@collect
            settledOwnerText = value
            val caret = currentProbe?.consumePendingCaret(value.length)
            state.edit {
                replace(0, length, value)
                if (caret != null) {
                    selection = TextRange(caret.coerceIn(0, value.length))
                } else {
                    placeCursorAtEnd()
                }
            }
            limiter.acceptProgrammatic(value)
            currentProbe?.record(state.selection)
        }
    }

    // Selection out, so dictation can insert at the caret.
    LaunchedEffect(state) {
        snapshotFlow { state.selection }.collect { selection -> currentProbe?.record(selection) }
    }

    return remember(state, inputTransformation) {
        ComposerTextState(state = state, inputTransformation = inputTransformation)
    }
}
