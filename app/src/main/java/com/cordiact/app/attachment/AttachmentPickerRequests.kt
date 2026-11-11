package com.cordiact.app.attachment

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * The only two ways P0 chooses files.
 *
 * Both return `content://` URIs; no storage permission is requested, no path is guessed and no
 * full-library photo permission is needed for picking a few images.
 */
object AttachmentPickerRequests {
    const val MAX_PICKED_ITEMS = 10

    /** Images only, through the system photo picker (with the AndroidX fallback). */
    fun imagePicker(): ActivityResultContracts.PickMultipleVisualMedia =
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_ITEMS)

    fun singleImagePicker(): ActivityResultContracts.PickVisualMedia =
        ActivityResultContracts.PickVisualMedia()

    fun imageRequest(): PickVisualMediaRequest =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    /** Documents: the P0 allow-list only, filtered by the system picker. */
    fun documentPicker(): ActivityResultContracts.OpenMultipleDocuments =
        ActivityResultContracts.OpenMultipleDocuments()

    val documentMimeTypes: Array<String> = arrayOf(
        "image/*",
        "application/pdf",
        "text/*",
    )
}
