package ai.oriveo.community.feature.chat.composer

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import ai.oriveo.community.core.util.InputSizeLimitExceededException
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clipboard image intake for the composer: which items count as images, what a paste does to the
 * cache, and — most importantly — that a text clipboard is never treated as an image, so the plain
 * text paste and [ai.oriveo.community.core.util.ComposerLengthLimiter] keep behaving as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerClipboardImageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val imageUri: Uri = Uri.parse("content://media/external/images/media/42")
    private val otherUri: Uri = Uri.parse("content://com.example.notes/7")

    // --- what counts as a pasted image ---

    @Test
    fun `an image uri on the clipboard is picked`() {
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(imageUri))

        assertEquals(listOf(imageUri), ComposerClipboardImage.imageUris(clip))
    }

    @Test
    fun `a plain text clipboard yields no image`() {
        val clip = ClipData.newPlainText("text", "hello from the clipboard")

        assertTrue(ComposerClipboardImage.imageUris(clip).isEmpty())
        assertFalse("a text clipboard must not be handled as an image paste", ComposerClipboardImage.hasImage(context))
    }

    @Test
    fun `a mixed text and image clipboard keeps the image and leaves the text in place`() {
        val clip = ClipData(
            ClipDescription("mixed", arrayOf("text/plain", "image/png")),
            ClipData.Item("caption"),
        ).apply { addItem(ClipData.Item(imageUri)) }

        assertEquals(listOf(imageUri), ComposerClipboardImage.imageUris(clip))
        // The clip is read, never rewritten: BasicTextField still pastes the text item.
        assertEquals("caption", clip.getItemAt(0).text.toString())
        assertEquals(2, clip.itemCount)
    }

    @Test
    fun `a uri of another type is not an image`() {
        val clip = ClipData(
            ClipDescription("link", arrayOf("text/uri-list")),
            ClipData.Item(otherUri),
        )

        assertTrue(ComposerClipboardImage.imageUris(clip).isEmpty())
    }

    @Test
    fun `an unspecified mime type is resolved through the content resolver`() {
        val clip = ClipData(ClipDescription("unknown", arrayOf("*/*")), ClipData.Item(imageUri))

        assertEquals(
            listOf(imageUri),
            ComposerClipboardImage.imageUris(clip) { uri -> if (uri == imageUri) "image/jpeg" else "text/plain" },
        )
        assertTrue(
            "a resolver that reports text must keep the paste a text paste",
            ComposerClipboardImage.imageUris(clip) { "text/plain" }.isEmpty(),
        )
        assertTrue(
            "an unresolvable uri must not be attached",
            ComposerClipboardImage.imageUris(clip) { null }.isEmpty(),
        )
    }

    @Test
    fun `an image added after a text item is still found when the clip only declares text`() {
        // addItem() does not extend the description: "text/plain" describes the caption only, and
        // reusing it for the next item would hide the image the user pasted.
        val clip = ClipData(ClipDescription("mixed", arrayOf("text/plain")), ClipData.Item("caption"))
            .apply { addItem(ClipData.Item(imageUri)) }

        assertEquals(
            "the image after the text must be resolved through the provider",
            listOf(imageUri),
            ComposerClipboardImage.imageUris(clip) { "image/png" },
        )
    }

    @Test
    fun `a homogeneous image clip keeps every item without asking the provider`() {
        val clip = ClipData(ClipDescription("images", arrayOf("image/png")), ClipData.Item(imageUri))
        clip.addItem(ClipData.Item(Uri.parse("content://media/external/images/media/43")))
        clip.addItem(ClipData.Item(Uri.parse("content://media/external/images/media/44")))

        val found = ComposerClipboardImage.imageUris(clip) {
            error("a single declared image type describes the whole clip, so the provider is not needed")
        }

        assertEquals(3, found.size)
    }

    @Test
    fun `a provider that throws leaves the clip without images`() {
        val clip = ClipData(ClipDescription("unknown", arrayOf("*/*")), ClipData.Item(imageUri))

        assertTrue(
            "an unknown type is not an image, and a dead provider must not throw",
            ComposerClipboardImage.imageUris(clip) { throw SecurityException("no provider") }.isEmpty(),
        )
    }

    @Test
    fun `state is false instead of crashing when the provider throws`() {
        val clip = ClipData(ClipDescription("unknown", arrayOf("*/*")), ClipData.Item(imageUri))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)

        val state = ComposerClipboardImage.state(context) { throw SecurityException("no provider") }

        assertFalse("a throwing provider must read as \"no image\", not as a crash", state.hasImage)
    }

    @Test
    fun `a throwing provider does not stop the paste`() = runTest {
        val bytes = byteArrayOf(9, 8, 7)
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(imageUri))

        val uris = ComposerClipboardImage.materialize(
            context = context,
            clip = clip,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { _, file -> Uri.fromFile(file) },
            openStream = { ByteArrayInputStream(bytes) },
            typeOf = { throw SecurityException("no provider") },
        )

        assertEquals(1, uris.size)
        val file = File(uris.single().path!!)
        assertArrayEquals("the copy is still written", bytes, file.readBytes())
        assertTrue("the type is unavailable, so the file falls back to .jpg", file.name.endsWith(".jpg"))
    }

    @Test
    fun `a text item without a uri inside an image clip is ignored`() {
        val clip = ClipData(ClipDescription("mixed", arrayOf("image/png")), ClipData.Item("no uri here"))

        assertTrue(ComposerClipboardImage.imageUris(clip).isEmpty())
    }

    @Test
    fun `pastes are capped like one photo picker selection`() {
        val clip = ClipData(ClipDescription("many", arrayOf("image/png")), ClipData.Item(Uri.parse("content://img/0")))
        repeat(ComposerClipboardImage.MAX_IMAGES_PER_PASTE + 3) { index ->
            clip.addItem(ClipData.Item(Uri.parse("content://img/${index + 1}")))
        }

        assertEquals(ComposerClipboardImage.MAX_IMAGES_PER_PASTE, ComposerClipboardImage.imageUris(clip).size)
    }

    // --- Ctrl+V / Cmd+V ---

    @Test
    fun `the paste shortcut is only taken when an image is on the clipboard`() {
        assertEquals(
            ComposerClipboardImage.PasteShortcut.IMAGE_ONLY,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = true, isCtrlPressed = true, isMetaPressed = false, hasImage = true, hasText = false,
            ),
        )
        assertEquals(
            "Cmd+V on a keyboard that reports Meta",
            ComposerClipboardImage.PasteShortcut.IMAGE_ONLY,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = true, isCtrlPressed = false, isMetaPressed = true, hasImage = true, hasText = false,
            ),
        )
    }

    @Test
    fun `a mixed clipboard keeps the text paste and still attaches the image`() {
        assertEquals(
            ComposerClipboardImage.PasteShortcut.IMAGE_AND_TEXT,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = true, isCtrlPressed = true, isMetaPressed = false, hasImage = true, hasText = true,
            ),
        )
        val mixed = ClipData(
            ClipDescription("mixed", arrayOf("text/plain", "image/png")),
            ClipData.Item("a caption"),
        ).apply { addItem(ClipData.Item(imageUri)) }
        assertTrue("the caption must be pasted by the field itself", ComposerClipboardImage.hasText(mixed))
    }

    @Test
    fun `plain text paste is never intercepted`() {
        // Text clipboard (hasImage = false): the event reaches the field and pastes as before.
        assertEquals(
            ComposerClipboardImage.PasteShortcut.NONE,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = true, isCtrlPressed = true, isMetaPressed = false, hasImage = false, hasText = true,
            ),
        )
        // No modifier, another key, or the key-up half of the same press: also not ours.
        assertEquals(
            ComposerClipboardImage.PasteShortcut.NONE,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = true, isCtrlPressed = false, isMetaPressed = false, hasImage = true, hasText = false,
            ),
        )
        assertEquals(
            ComposerClipboardImage.PasteShortcut.NONE,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = true, isV = false, isCtrlPressed = true, isMetaPressed = false, hasImage = true, hasText = false,
            ),
        )
        assertEquals(
            ComposerClipboardImage.PasteShortcut.NONE,
            ComposerClipboardImage.pasteShortcut(
                isKeyDown = false, isV = true, isCtrlPressed = true, isMetaPressed = false, hasImage = true, hasText = false,
            ),
        )
        assertFalse(
            "a plain text clip has no text to hand over twice",
            ComposerClipboardImage.hasText(ClipData.newPlainText("text", "hello")),
        )
    }

    // --- copying into the cache ---

    @Test
    fun `materialize copies the image into the cache and returns a uri for it`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(imageUri))

        val uris = ComposerClipboardImage.materialize(
            context = context,
            clip = clip,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { _, file -> Uri.fromFile(file) },
            openStream = { ByteArrayInputStream(bytes) },
        )

        assertEquals(1, uris.size)
        val file = File(uris.single().path!!)
        assertTrue(
            "the copy must live in the dedicated cache directory",
            file.parentFile?.name == ComposerClipboardImage.CACHE_DIR_NAME,
        )
        assertTrue("the sweep only knows its own files", file.name.startsWith("paste_"))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun `materialize keeps the clipboard uri when the image cannot be read`() = runTest {
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(otherUri))

        // No stream at all, like a source app that has already released its grant.
        val uris = ComposerClipboardImage.materialize(
            context = context,
            clip = clip,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { _, file -> Uri.fromFile(file) },
            openStream = { null },
        )

        assertEquals(listOf(otherUri), uris)
        assertTrue(
            "nothing may be written for an unreadable image",
            File(context.cacheDir, ComposerClipboardImage.CACHE_DIR_NAME).listFiles().isNullOrEmpty(),
        )
    }

    @Test
    fun `a throwing provider falls back to the clipboard uri`() = runTest {
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(otherUri))

        val uris = ComposerClipboardImage.materialize(
            context = context,
            clip = clip,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { _, file -> Uri.fromFile(file) },
            openStream = { throw java.io.FileNotFoundException("gone") },
        )

        assertEquals(listOf(otherUri), uris)
    }

    @Test
    fun `an oversized image is handed over unchanged so the existing limit dialog still fires`() = runTest {
        val clip = ClipData(ClipDescription("image", arrayOf("image/png")), ClipData.Item(imageUri))

        val uris = ComposerClipboardImage.materialize(
            context = context,
            clip = clip,
            ioDispatcher = Dispatchers.Unconfined,
            uriForFile = { _, file -> Uri.fromFile(file) },
            openStream = { throw InputSizeLimitExceededException(1) },
        )

        assertEquals(listOf(imageUri), uris)
        assertTrue(
            "an oversized image is not copied into the cache",
            File(context.cacheDir, ComposerClipboardImage.CACHE_DIR_NAME).listFiles().isNullOrEmpty(),
        )
    }

    // --- cache hygiene ---

    @Test
    fun `sweep removes copies older than the age limit`() {
        val dir = tempFolder.newFolder()
        val stale = File(dir, "paste_stale.jpg").apply { writeBytes(ByteArray(4)) }
        val fresh = File(dir, "paste_fresh.jpg").apply { writeBytes(ByteArray(4)) }
        val now = 10_000_000L
        stale.setLastModified(now - ComposerClipboardImage.MAX_CACHED_AGE_MILLIS - 1)
        fresh.setLastModified(now - 1_000L)

        ComposerClipboardImage.sweep(dir, now = now)

        assertFalse("a copy an hour old has done its job", stale.exists())
        assertTrue("a copy that may still be importing must stay", fresh.exists())
    }

    @Test
    fun `sweep keeps the newest copies when the count limit is exceeded`() {
        val dir = tempFolder.newFolder()
        val now = 10_000_000L
        val files = (0 until ComposerClipboardImage.MAX_CACHED_FILES + 5).map { index ->
            File(dir, "paste_$index.jpg").apply {
                writeBytes(ByteArray(4))
                setLastModified(now - (ComposerClipboardImage.MAX_CACHED_FILES + 5 - index) * 1_000L)
            }
        }

        ComposerClipboardImage.sweep(dir, now = now)

        assertEquals(ComposerClipboardImage.MAX_CACHED_FILES, dir.listFiles()!!.size)
        assertFalse("the oldest copy goes first", files.first().exists())
        assertTrue("the newest copy stays", files.last().exists())
    }

    @Test
    fun `sweep honours the byte budget`() {
        val dir = tempFolder.newFolder()
        val now = 10_000_000L
        val big = 20L * 1024L * 1024L
        val files = (0 until 4).map { index ->
            File(dir, "paste_big_$index.jpg").apply {
                // Sparse file: the budget is about length, not about writing 20 MB four times.
                java.io.RandomAccessFile(this, "rw").use { it.setLength(big) }
                setLastModified(now - (4 - index) * 1_000L)
            }
        }

        ComposerClipboardImage.sweep(dir, now = now, maxBytes = ComposerClipboardImage.MAX_CACHED_BYTES)

        val keptBytes = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("the cache must stay under its budget: $keptBytes", keptBytes <= ComposerClipboardImage.MAX_CACHED_BYTES)
        assertFalse("the oldest copy goes first", files.first().exists())
    }

    @Test
    fun `sweep leaves unrelated files alone`() {
        val dir = tempFolder.newFolder()
        val foreign = File(dir, "chat_capture_123.jpg").apply { writeBytes(ByteArray(4)) }
        foreign.setLastModified(0L)

        ComposerClipboardImage.sweep(dir, now = 10_000_000L)

        assertTrue("only pasted copies belong to this sweep", foreign.exists())
    }
}
