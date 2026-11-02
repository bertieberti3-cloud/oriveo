package ai.oriveo.community.feature.chat.composer

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ai.oriveo.community.core.util.InputSizeLimitExceededException
import ai.oriveo.community.core.util.UNKNOWN_INPUT_SIZE
import ai.oriveo.community.core.util.generateUuidString
import ai.oriveo.community.core.util.readBytesLimited
import ai.oriveo.community.feature.chat.attachments.AttachmentImportPolicy
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Images on the system clipboard, handed to the composer's regular attachment path.
 *
 * Nothing here imports an attachment by itself: a pasted image is copied to a cache file and the
 * resulting URI goes to the same `onProcessImageUri` the photo picker and the camera use, so
 * compression ([ai.oriveo.community.core.data.attachment.AttachmentStore.saveCompressedImage]),
 * the size and count limits and the preview tray stay exactly as they are.
 *
 * Why the cache copy: the clipboard grants read access to a `content://` URI only while the clip
 * is on the clipboard, and the source app can change it at any moment (and its provider may not
 * answer a stream at all). A local copy is readable for as long as the import needs it, and it
 * gives [ai.oriveo.community.feature.chat.attachments.AttachmentProcessor.processImage] a real
 * file name instead of whatever the provider's `lastPathSegment` happens to be.
 *
 * The copies live under `cacheDir/clipboard_images` and never grow without bound: every paste
 * first sweeps the directory ([sweep]), removing files older than [MAX_CACHED_AGE_MILLIS] and then
 * the oldest ones until both [MAX_CACHED_FILES] and [MAX_CACHED_BYTES] hold, and one paste never
 * writes more than [MAX_CACHED_BYTES] in total — past that, the remaining images are handed over
 * from their clipboard URI instead of being copied.
 */
internal object ComposerClipboardImage {

    /** Thumbnail/preview tray texture: pastes are bounded like one photo-picker selection. */
    const val MAX_IMAGES_PER_PASTE = 10

    internal const val CACHE_DIR_NAME = "clipboard_images"

    /** Long enough for an import queued behind a large PDF, short enough that nothing lingers. */
    internal const val MAX_CACHED_AGE_MILLIS = 60L * 60L * 1000L

    internal const val MAX_CACHED_FILES = 24

    internal const val MAX_CACHED_BYTES = 50L * 1024L * 1024L

    /** MIME of the copy is not stored anywhere: the store re-encodes to JPEG and reports its own. */
    private const val CACHE_FILE_PREFIX = "paste_"

    /** What the clipboard holds for the composer: an image to attach and/or text the field pastes by itself. */
    data class ClipboardState(val hasImage: Boolean, val hasText: Boolean)

    /**
     * Whether the clipboard currently holds at least one usable image.
     *
     * The clip is read (not its payload, which is opened only by [materialize]), the same way
     * [ai.oriveo.community.ui.component.markdown.NoteSelectionTextToolbar] reads it. Callers run
     * while the app is in the foreground, so this can never read the clipboard in the background.
     */
    fun hasImage(context: Context): Boolean = state(context).hasImage

    /** One clipboard read for the paste shortcut: an image to attach and text the field would paste. */
    fun state(
        context: Context,
        /**
         * Untrusted source of a URI's MIME type: the ContentResolver in production, a seam in the
         * tests. A provider that throws is answered as "unknown", never as a crash.
         */
        typeOf: (Uri) -> String? = { uri -> context.contentResolver.getType(uri) },
    ): ClipboardState {
        val clip = primaryClip(context)
        return ClipboardState(
            hasImage = imageUris(clip, typeOf).isNotEmpty(),
            hasText = hasText(clip),
        )
    }

    /**
     * Image URIs of [clip], in clipboard order, at most [MAX_IMAGES_PER_PASTE].
     *
     * [resolveMimeType] is consulted only when the clip's own description cannot answer (a mixed
     * clip reports `* / *` per item, and some apps put nothing at all); a URI of another type is
     * left alone, which is what keeps a text-only clipboard a text paste.
     */
    fun imageUris(
        clip: ClipData?,
        resolveMimeType: (Uri) -> String? = { null },
    ): List<Uri> {
        if (clip == null) return emptyList()
        val description = clip.description
        // A provider that is missing, dead or permission-protected throws instead of answering;
        // "unknown type" must mean "not an image", never a crash while composing the chat.
        val typeOf: (Uri) -> String? = { uri -> runCatching { resolveMimeType(uri) }.getOrNull() }
        val result = LinkedHashSet<Uri>()
        for (index in 0 until clip.itemCount) {
            if (result.size >= MAX_IMAGES_PER_PASTE) break
            val uri = clip.getItemAt(index)?.uri ?: continue
            val mimeType = mimeTypeAt(description, index)
            if (isImageMimeType(mimeType) || (isUnspecifiedMimeType(mimeType) && isImageMimeType(typeOf(uri)))) {
                result += uri
            }
        }
        return result.toList()
    }

    /**
     * MIME type of one clipboard item.
     *
     * `ClipData.addItem` appends items without extending the description, so a clip can carry more
     * items than it declares types for. A single declared *image* type still describes the whole
     * clip (`newUri` + `addItem`, several photos from one app); any other single type describes
     * only the first item, and reusing it for the rest is what let a text item hide the image that
     * was added after it. Null means "ask the provider", which [imageUris] already does.
     */
    private fun mimeTypeAt(description: ClipDescription, index: Int): String? {
        if (description.mimeTypeCount == 0) return null
        if (index < description.mimeTypeCount) return description.getMimeType(index)
        val only = description.getMimeType(0)
        return if (description.mimeTypeCount == 1 && isImageMimeType(only)) only else null
    }

    /**
     * What Ctrl+V / Cmd+V should do with the clipboard.
     *
     * [IMAGE_ONLY] consumes the key so the field does not paste the URI of the image as text;
     * [IMAGE_AND_TEXT] attaches the image **and** lets the event through, so a clip that carries a
     * caption still pastes it — the same behaviour as the web client, where the paste handler adds
     * the image files without cancelling the browser's text paste. [NONE] leaves the event to
     * `BasicTextField`, which is what keeps a plain text paste and `ComposerLengthLimiter` exactly
     * as they were.
     */
    enum class PasteShortcut { NONE, IMAGE_ONLY, IMAGE_AND_TEXT }

    fun pasteShortcut(
        isKeyDown: Boolean,
        isV: Boolean,
        isCtrlPressed: Boolean,
        isMetaPressed: Boolean,
        hasImage: Boolean,
        hasText: Boolean,
    ): PasteShortcut = when {
        !isKeyDown || !isV || !(isCtrlPressed || isMetaPressed) -> PasteShortcut.NONE
        !hasImage -> PasteShortcut.NONE
        hasText -> PasteShortcut.IMAGE_AND_TEXT
        else -> PasteShortcut.IMAGE_ONLY
    }

    /** Whether the clip also carries text the user may want pasted into the field. */
    fun hasText(clip: ClipData?): Boolean {
        if (clip == null) return false
        return (0 until clip.itemCount).any { index ->
            val item = clip.getItemAt(index)
            !item.text.isNullOrBlank() || !item.htmlText.isNullOrBlank()
        }
    }

    /** The primary clip, or null when the service or the clip is unavailable. */
    fun primaryClip(context: Context): ClipData? = try {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
    } catch (_: SecurityException) {
        // Android 10+ hides the clipboard from background apps; an OEM may throw instead of
        // returning null, and that must not take the composer down.
        null
    }

    /**
     * Copies the images of [clip] into the cache and returns one URI per image, ready for
     * `ChatViewModel.processImageUri`.
     *
     * An image that cannot be copied or read is returned as its original clipboard URI instead: the
     * import then fails, or reports "too large", through the existing pipeline rather than being
     * dropped silently.
     */
    suspend fun materialize(
        context: Context,
        clip: ClipData? = primaryClip(context),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        uriForFile: (Context, File) -> Uri = ::fileProviderUri,
        /** Seam for the tests: the real one is the resolver's stream, which throws when no provider answers. */
        openStream: (Uri) -> InputStream? = { uri -> context.contentResolver.openInputStream(uri) },
        /** Seam for the tests: the real one is the resolver's type, which throws for a dead provider. */
        typeOf: (Uri) -> String? = { uri -> context.contentResolver.getType(uri) },
    ): List<Uri> {
        val uris = imageUris(clip, typeOf)
        if (uris.isEmpty()) return emptyList()
        val directory = File(context.cacheDir, CACHE_DIR_NAME)
        return withContext(ioDispatcher) {
            sweep(directory, now = System.currentTimeMillis())
            // One paste can carry ten 25 MB images; keep the copies inside the same budget the
            // sweep enforces instead of letting a single paste fill the cache.
            var remainingBytes = MAX_CACHED_BYTES
            uris.map { uri ->
                val copy = copyToCache(context, uri, directory, uriForFile, openStream, typeOf, remainingBytes)
                remainingBytes -= copy.bytesWritten
                copy.uri
            }
        }
    }

    /**
     * Keeps the cache directory bounded: files past [MAX_CACHED_AGE_MILLIS] go first, then the
     * oldest files until at most [MAX_CACHED_FILES] and [MAX_CACHED_BYTES] remain. Failures are
     * ignored: a file that cannot be deleted (an import reading it right now) is swept next time.
     */
    internal fun sweep(
        directory: File,
        now: Long,
        maxAgeMillis: Long = MAX_CACHED_AGE_MILLIS,
        maxFiles: Int = MAX_CACHED_FILES,
        maxBytes: Long = MAX_CACHED_BYTES,
    ) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.startsWith(CACHE_FILE_PREFIX) }
            ?: return
        var kept = files.sortedByDescending { it.lastModified() }
            .filter { file -> now - file.lastModified() <= maxAgeMillis }
        var bytes = kept.sumOf { it.length() }
        while (kept.isNotEmpty() && (kept.size > maxFiles || bytes > maxBytes)) {
            val oldest = kept.last()
            kept = kept.dropLast(1)
            bytes -= oldest.length()
            runCatching { oldest.delete() }
        }
        (files - kept.toSet()).forEach { file -> runCatching { file.delete() } }
    }

    /** A clipboard image that is ready to hand to the importer, and how much cache it took. */
    private class CopyOutcome(val uri: Uri, val bytesWritten: Long)

    private fun copyToCache(
        context: Context,
        uri: Uri,
        directory: File,
        uriForFile: (Context, File) -> Uri,
        openStream: (Uri) -> InputStream?,
        typeOf: (Uri) -> String?,
        byteBudget: Long,
    ): CopyOutcome {
        val bytes = try {
            openStream(uri)?.use { stream ->
                stream.readBytesLimited(maxBytes = AttachmentImportPolicy.MAX_ATTACHMENT_BYTES, expectedBytes = UNKNOWN_INPUT_SIZE)
            }
        } catch (_: InputSizeLimitExceededException) {
            // The processor reports "attachment too large" from the original URI; copying it first
            // would spend the same heap only to throw it away.
            return CopyOutcome(uri, 0)
        } catch (_: Exception) {
            return CopyOutcome(uri, 0)
        } ?: return CopyOutcome(uri, 0)

        // The cache budget is spent: the import still runs, from the clipboard URI itself.
        if (bytes.size > byteBudget) return CopyOutcome(uri, 0)

        // The type only picks the file extension; a provider that throws leaves the default .jpg.
        val mimeType = runCatching { typeOf(uri) }.getOrNull()
        val file = File(directory, CACHE_FILE_PREFIX + generateUuidString() + extensionFor(mimeType))
        return try {
            if (!directory.exists()) directory.mkdirs()
            file.writeBytes(bytes)
            CopyOutcome(uriForFile(context, file), bytes.size.toLong())
        } catch (_: Exception) {
            runCatching { file.delete() }
            CopyOutcome(uri, 0)
        }
    }

    private fun fileProviderUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun isImageMimeType(mimeType: String?): Boolean =
        mimeType?.trim()?.startsWith("image/", ignoreCase = true) == true

    private fun isUnspecifiedMimeType(mimeType: String?): Boolean =
        mimeType.isNullOrBlank() || mimeType.trim() == "*/*"

    /** A hint for the file name only; [AttachmentProcessor.processImage] replaces the MIME with the stored JPEG's. */
    private fun extensionFor(mimeType: String?): String = when (mimeType?.trim()?.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/heic", "image/heif" -> ".heic"
        "image/bmp" -> ".bmp"
        else -> ".jpg"
    }
}
