package org.unityconnect.companion

import android.Manifest
import android.content.ContentResolver
import android.util.Size
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

// Robolectric's native ImageDecoder cannot decode a provider FD. Substitute
// only that OS thumbnail boundary; the production URI routing and JPEG encoder run.
@Implements(ContentResolver::class)
class GalleryThumbnailResolver : ShadowContentResolver() {
 companion object {var lastUri: Uri?=null}
 @Implementation(minSdk=29)
 fun loadThumbnail(uri: Uri, size: Size, cancellation: CancellationSignal?): Bitmap {
  lastUri=uri
  return Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
 }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[GalleryThumbnailResolver::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryTest {
 private class Provider(val image: File) : ContentProvider() {
  override fun onCreate()=true
  override fun getType(uri: Uri)="image/png"
  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
   val columns=projection ?: emptyArray();val video=uri.path.orEmpty().contains("video")
   return MatrixCursor(columns).apply {addRow(columns.map { when(it) {
    "_id" -> if(video)2 else 1
    "_display_name" -> if(video)"movie.mp4" else "photo.jpg"
    "_size" -> 1234L
    "date_added" -> if(video)200L else 100L
    "width" -> 1920
    "height" -> 1080
    "mime_type" -> if(video)"video/mp4" else "image/jpeg"
    "duration" -> 45000L
    else -> null
   } }.toTypedArray<Any?>())}
  }
  override fun openTypedAssetFile(uri: Uri, mime: String, options: Bundle?, cancellation: CancellationSignal?)=
   AssetFileDescriptor(ParcelFileDescriptor.open(image,ParcelFileDescriptor.MODE_READ_ONLY),0,AssetFileDescriptor.UNKNOWN_LENGTH)
  override fun insert(uri: Uri, values: ContentValues?): Uri?=null
  override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?)=0
  override fun delete(uri: Uri, selection: String?, args: Array<out String>?)=0
 }
 @Test fun galleryCombinesTypesSortsByDateAndEncodesVideoThumbnails() {
  val controller=Robolectric.buildService(CompanionService::class.java).create();val service=controller.get()
  try {
   shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
   service.getSharedPreferences("settings",0).edit().putBoolean("enable.photos",true).putBoolean("enable.files",true).commit()
   val image=File(service.cacheDir,"thumbnail.png")
   val bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
   image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
   val provider=Provider(image);provider.attachInfo(service,ProviderInfo().apply {authority="media"})
   ShadowContentResolver.registerProviderInternal("media",provider)
   val gallery=service.features.modules.getValue("photos")
   val result=gallery.command("list",JSONObject())!!;val items=result.getJSONArray("items")
   assertEquals(2,items.length());assertEquals("video",items.getJSONObject(0).getString("kind"));assertEquals(45000,items.getJSONObject(0).getLong("duration"))
   assertEquals("image",items.getJSONObject(1).getString("kind"));assertTrue(result.getBoolean("video_supported"))
   val id=items.getJSONObject(0).getString("id")
   val preview=gallery.command("preview",JSONObject().put("id",id).put("edge",256))!!.getJSONObject("preview")
   assertEquals("image/jpeg",preview.getString("mime"));assertTrue(preview.getInt("width")<=256)
   assertTrue(GalleryThumbnailResolver.lastUri.toString().contains("/video/media/2"))
   gallery.command("list",JSONObject())
   assertThrows(IllegalStateException::class.java) {gallery.command("preview",JSONObject().put("id",id))}
   shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES)
   val videos=gallery.command("list",JSONObject())!!.getJSONArray("items")
   assertEquals(1,videos.length());assertEquals("video",videos.getJSONObject(0).getString("kind"))
  } finally {controller.destroy()}
 }
}
