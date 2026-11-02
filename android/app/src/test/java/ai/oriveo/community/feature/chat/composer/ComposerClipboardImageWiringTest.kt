package ai.oriveo.community.feature.chat.composer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the clipboard-image wiring to the existing attachment pipeline: a pasted image goes out
 * through the same `onProcessImageUri` as the picker and the camera (never through a second import
 * path), the text field only intercepts Ctrl/Cmd+V when the clipboard holds an image, and the
 * composer still builds its value through rememberComposerLengthLimitedField.
 *
 * A source scan, like [ChatComposerDictationWiringTest]: EnhancedComposer is too large to compose
 * as a whole in a unit test.
 */
class ComposerClipboardImageWiringTest {

    private val source: String by lazy {
        File("src/main/java/ai/oriveo/community/feature/chat/composer/EnhancedComposer.kt").readText()
    }

    private val entryButtonSource: String by lazy {
        File("src/main/java/ai/oriveo/community/feature/chat/composer/ComposerAttachmentEntryButton.kt").readText()
    }

    @Test
    fun `a pasted image is imported through the existing image pipeline`() {
        assertTrue(
            "materialize must feed onProcessImageUri, the same callback the picker uses",
            source.contains("ComposerClipboardImage.materialize(composerContext).forEach(onProcessImageUri)"),
        )
        assertFalse(
            "no second import path: the paste must not call the processor or the store directly",
            source.contains("AttachmentProcessor(") || source.contains("attachmentStore.saveCompressedImage"),
        )
    }

    @Test
    fun `the paste shortcut is gated on the clipboard holding an image`() {
        assertTrue(
            "composer must pass the shortcut to the field",
            source.contains("onPasteImageShortcut = if (controlsEnabled) pasteClipboardImage else null"),
        )
        assertTrue(
            "the key handler must re-read the clipboard, so a text clipboard is never consumed",
            source.contains("clipboardState = { ComposerClipboardImage.state(composerContext) }"),
        )
        val fieldAt = source.indexOf("private fun ComposerTextField(")
        assertTrue("ComposerTextField is missing", fieldAt >= 0)
        val handler = source.substring(fieldAt)
        assertTrue(
            "the field must decide through ComposerClipboardImage.pasteShortcut",
            handler.contains("ComposerClipboardImage.pasteShortcut("),
        )
        assertTrue(
            "the shortcut must stay a preview so the event reaches BasicTextField when it is text",
            handler.contains(".onPreviewKeyEvent { event ->"),
        )
        assertTrue(
            "a text-only clipboard must fall through to the field",
            handler.contains("ComposerClipboardImage.PasteShortcut.NONE -> false"),
        )
        assertTrue(
            "the length-limited value must stay the field's only value source",
            handler.contains("rememberComposerLengthLimitedField(text = text, onTextChange = onValueChange, selectionProbe = selectionProbe)"),
        )
        assertTrue(
            "text edits, including a normal paste, must still flow through the limiter",
            handler.contains("onValueChange = field.onValueChange"),
        )
    }

    @Test
    fun `the attach menu offers pasting only when the image entry is usable`() {
        assertTrue(
            "EnhancedComposer must compute paste availability from the image capability and the clipboard",
            source.contains("val pasteImageAvailable = controlsEnabled") &&
                source.contains("attachmentEntry.image.enabled &&") &&
                source.contains("clipboardHasImage"),
        )
        assertTrue(
            "the entry button must receive the paste entry",
            source.contains("showPasteImage = pasteImageAvailable") && source.contains("onPasteImage = {"),
        )
        assertTrue(
            "the option count must include the paste entry so the menu opens for an image-only model",
            source.contains("count { it.shown } +") && source.contains("if (pasteImageAvailable) 1 else 0"),
        )
        assertTrue(
            "ComposerAttachmentEntryButton must render the paste entry first",
            entryButtonSource.contains("R.string.composer_paste_image"),
        )
    }
}
