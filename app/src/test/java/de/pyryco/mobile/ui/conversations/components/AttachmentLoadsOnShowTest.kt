package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.model.MessageAttachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1329: only what draws as a picture is fetched on sight, as on desktop. */
class AttachmentLoadsOnShowTest {
    @Test
    fun imageName_isDesktopsExtensionSet_exactlyAndInAnyCase() {
        for (extension in listOf("png", "jpg", "jpeg", "gif", "webp", "avif", "bmp")) {
            assertTrue(extension, isImageAttachmentName("photo.$extension"))
            assertTrue(extension, isImageAttachmentName("PHOTO.${extension.uppercase()}"))
        }
        for (name in listOf("report.pdf", "png", "photo.png.zip", "photo.", "photo.svg", "photo.tiff", "photo.pngx", ".png ")) {
            assertFalse(name, isImageAttachmentName(name))
        }
        // Only the text after the last dot counts.
        assertTrue(isImageAttachmentName("archive.zip.png"))
        assertTrue(isImageAttachmentName(".png"))
    }

    @Test
    fun aKnownType_decides_whateverTheName() {
        assertTrue(loadsOnShow(MessageAttachment(ID, "report.pdf", "image/png")))
        assertTrue(loadsOnShow(MessageAttachment(ID, null, "IMAGE/JPEG")))
        assertFalse(loadsOnShow(MessageAttachment(ID, "photo.png", "application/pdf")))
    }

    @Test
    fun aNameAlone_decidesByItsExtension() {
        assertTrue(loadsOnShow(MessageAttachment(ID, "photo.png", null)))
        assertTrue(loadsOnShow(MessageAttachment(ID, "photo.webp", " ")))
        assertFalse(loadsOnShow(MessageAttachment(ID, "report.pdf", null)))
        assertFalse(loadsOnShow(MessageAttachment(ID, "README", null)))
    }

    @Test
    fun neitherTypeNorName_loadsOnShow() {
        assertTrue(loadsOnShow(MessageAttachment(ID)))
        assertTrue(loadsOnShow(MessageAttachment(ID, " ", "")))
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
