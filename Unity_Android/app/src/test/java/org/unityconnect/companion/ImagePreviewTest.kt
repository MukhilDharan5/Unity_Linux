package org.unityconnect.companion
import org.junit.Assert.*
import org.junit.Test
class ImagePreviewTest {
 @Test fun scalesPortraitAndLandscapeWithoutUpscaling() {
  assertEquals(256 to 192, ImagePreview.fitSize(4000, 3000, 256))
  assertEquals(192 to 256, ImagePreview.fitSize(3000, 4000, 256))
  assertEquals(80 to 40, ImagePreview.fitSize(80, 40, 256))
  assertEquals(1 to 320, ImagePreview.fitSize(1, 80000, 320))
 }
 @Test(expected = IllegalArgumentException::class) fun rejectsOversizedPreviewRequest() { ImagePreview.fitSize(4000, 3000, 4096) }
 @Test(expected = IllegalArgumentException::class) fun rejectsInvalidImage() { ImagePreview.fitSize(0, 200, 256) }
}
