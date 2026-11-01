package org.unityconnect.companion

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenAndLockTest {
 private fun request() = JSONObject().put("request_id", "ab".repeat(16)).put("token", "01".repeat(32))
  .put("source", "phone").put("format", "jpeg").put("max_edge", 1280).put("max_pixels", 921600).put("max_bytes", 262144).put("fps", 15)
 @Test fun malformedAndUnboundedCaptureNegotiationIsRejected() {
  assertEquals("phone", ScreenRequest.parse(request()).source)
  for ((key,value) in listOf("source" to "shell", "token" to "bad", "fps" to 300, "format" to "raw", "max_bytes" to 99999999, "request_id" to "../path")) {
   assertThrows(IllegalArgumentException::class.java) { ScreenRequest.parse(request().put(key,value)) }
  }
 }
 @Test fun scaledCapturePreservesPortraitAndLandscapeBounds() {
  assertEquals(1280 to 720, screenDimensions(3840,2160))
  assertEquals(720 to 1280, screenDimensions(2160,3840))
  assertEquals(320 to 200, screenDimensions(320,200))
  assertThrows(IllegalArgumentException::class.java) { screenDimensions(0,2160) }
 }
 @Test fun remoteClicksMapImagePixelsAndRejectLetterboxMargins() {
  assertEquals(.5f to .5f, screenPoint(400,800,1280,720,200f,400f))
  assertNull(screenPoint(400,800,1280,720,200f,10f))
  assertEquals(0f to 0f, screenPoint(400,800,1280,720,-1f,-1f,true))
  assertEquals(.5f to .5f, screenPoint(800,400,1280,720,400f,200f))
  assertNull(screenPoint(800,400,1280,720,10f,200f))
  assertNull(screenPoint(0,800,1280,720,0f,10f))
  assertNull(screenPoint(400,800,1280,720,Float.NaN,10f))
 }
 @Test fun touchPressDragReleaseAndKeyboardToggleUseOnlyTheViewerSession() {
  val context = org.robolectric.RuntimeEnvironment.getApplication()
  val view = RemoteScreenView(context)
  val commands = mutableListOf<JSONObject>()
  view.allowed = { true }; view.send = { commands.add(it) }; view.imageSize = { 1280 to 720 }
  view.layout(0,0,400,800)
  fun touch(action: Int, x: Float, y: Float) {
   android.view.MotionEvent.obtain(0,100,action,x,y,0).also { view.onTouchEvent(it); it.recycle() }
  }
  touch(android.view.MotionEvent.ACTION_DOWN,200f,10f); assertTrue(commands.isEmpty())
  touch(android.view.MotionEvent.ACTION_DOWN,200f,400f)
  touch(android.view.MotionEvent.ACTION_MOVE,300f,400f)
  touch(android.view.MotionEvent.ACTION_UP,300f,400f)
  assertEquals(listOf("pointer","motion","pointer"), commands.map { it.getString("kind") })
  assertTrue(commands.first().getBoolean("pressed")); assertFalse(commands.last().getBoolean("pressed"))
  assertEquals(.75,commands.last().getDouble("x"),.0001)
  commands.clear(); view.showKeyboard(true)
  assertTrue(view.onCheckIsTextEditor()); assertTrue(view.keyboardVisible)
  val connection = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
  connection.setComposingText("தமிழ்",1); assertTrue(commands.isEmpty())
  connection.commitText("தமிழ்😀",1)
  connection.finishComposingText()
  assertEquals(1,commands.size); assertEquals("தமிழ்😀",commands[0].getString("text"))
  connection.setComposingText("a😀",1); connection.deleteSurroundingTextInCodePoints(1,0); connection.finishComposingText()
  assertEquals("a",commands.last().getString("text"))
  connection.deleteSurroundingText(1,0); assertEquals("backspace",commands.last().getString("key"))
  connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER))
  connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_ENTER))
  assertEquals(1,commands.count { it.optString("key") == "enter" })
  view.showKeyboard(false); assertFalse(view.keyboardVisible); assertNull(view.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
  view.allowed = { false }; val count = commands.size
  touch(android.view.MotionEvent.ACTION_DOWN,200f,400f)
  assertEquals(count,commands.size)
 }
 @Test fun desktopDisplayModeIsExplicitAndLegacyRequestsStayViewOnly() {
  val data = request().put("source","desktop").put("control",true).put("blank_display",true)
  val parsed = ScreenRequest.parse(data); assertTrue(parsed.control); assertTrue(parsed.blankDisplay)
  assertFalse(ScreenRequest.parse(request()).control)
 }
 @Test fun extendedDisplayIsSeparateFromPrimaryAndDoesNotRequirePhoneInput() {
  val data=request().put("source","desktop").put("extend_display",true).put("control",false)
  val parsed=ScreenRequest.parse(data)
  assertTrue(parsed.extendDisplay); assertFalse(parsed.blankDisplay); assertFalse(parsed.control)
  assertThrows(IllegalArgumentException::class.java) { ScreenRequest.parse(data.put("blank_display",true)) }
  assertThrows(IllegalArgumentException::class.java) { ScreenRequest.parse(request().put("source","desktop").put("extend_display","true")) }
  assertThrows(IllegalArgumentException::class.java) { ScreenRequest.parse(request().put("extend_display",true)) }
 }
 @Test fun phoneExtendedDisplayActionRequestsASeparateMonitor() {
  val controller=Robolectric.buildService(CompanionService::class.java).create(); val service=controller.get()
  service.getSharedPreferences("settings",0).edit().putBoolean("enable.screen",true).commit()
  try { TestConnection(service).use { fixture ->
   fixture.lan.send(JSONObject().put("type","capabilities").put("capabilities",org.json.JSONArray()
    .put(JSONObject().put("id","screen").put("status","available"))))
   fixture.await { service.connection.desktopAvailable("screen") }
   val activity=Robolectric.buildActivity(MainActivity::class.java).setup()
   try {
    val button=activity.get().findViewById<android.view.View>(R.id.view_computer_extended_display)
    assertTrue(button.isEnabled); button.performClick()
    val command=fixture.lan.next { it.optString("type")=="request" && it.optString("feature")=="screen" }
    assertEquals("start",command.getString("action"))
    val data=command.getJSONObject("data")
    assertEquals("desktop",data.getString("source")); assertTrue(data.getBoolean("extend_display"))
    assertFalse(data.optBoolean("blank_display"))
   } finally { activity.pause().stop().destroy() }
  } } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun largeImagesAreRejectedBeforeDecodeAndJpegPixelsArePlayable() {
  fun jpeg(w: Int,h: Int): ByteArray {
   val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG,70,it); bitmap.recycle() }.toByteArray()
  }
  val decoded = decodeScreen(jpeg(720,1280)); assertEquals(1280,decoded.height); decoded.recycle()
  assertThrows(IllegalArgumentException::class.java) { decodeScreen(jpeg(1281,1)) }
  assertThrows(IllegalArgumentException::class.java) { decodeScreen(jpeg(1280,1280)) }
  assertThrows(IllegalArgumentException::class.java) { decodeScreen(ByteArray(262145)) }
  assertThrows(IllegalArgumentException::class.java) { decodeScreen(byteArrayOf(1,2,3,4)) }
 }
 @Test fun screenRowsWithMissingFinalPaddingArePackedWithoutStalePixels() {
  val source = ByteBuffer.wrap(byteArrayOf(1,2,3,4,5,6,7,8,99,99,99,99,9,10,11,12,13,14,15,16))
  val reusable = ByteBuffer.allocate(16)
  val packed = screenPixels(source,12,2,2,reusable)
  assertSame(reusable,packed)
  assertArrayEquals(ByteArray(16) { (it + 1).toByte() },ByteArray(16).also { packed.get(it) })
  assertEquals(0,source.position())
  val tight = screenPixels(ByteBuffer.wrap(ByteArray(16) { (it + 1).toByte() }),8,2,2)
  assertArrayEquals(ByteArray(16) { (it + 1).toByte() },ByteArray(16).also { tight.get(it) })
  assertThrows(IllegalArgumentException::class.java) { screenPixels(ByteBuffer.allocate(19),12,2,2) }
 }
 @Test fun phoneLockRequiresExplicitFeatureAndAdminActivation() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try {
   assertEquals("disabled",service.features.lock.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { service.features.lock.command("lock",JSONObject()) }
   service.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("enable.lock",true).commit()
   assertEquals("user_action_required",service.features.lock.capability().getString("status"))
   assertThrows(IllegalStateException::class.java) { service.features.lock.command("lock",JSONObject()) }
   val policy = service.getSystemService(DevicePolicyManager::class.java)
   shadowOf(policy).setActiveAdmin(DeviceLock.component(service))
   assertEquals("available",service.features.lock.capability().getString("status"))
   assertEquals("locked",service.features.lock.command("lock",JSONObject()).getString("state"))
   assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,DeviceLock.activation(service).action)
  } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
 @Test fun cancelledOrStaleConsentCannotCreateCaptureAndViewerSurvivesDisconnect() {
  val controller = Robolectric.buildService(CompanionService::class.java).create()
  val service = controller.get()
  try {
   assertEquals("disabled",service.features.screen.capability().getString("status"))
   service.features.screen.consent("ab".repeat(16), android.app.Activity.RESULT_OK, android.content.Intent())
   assertFalse(service.features.screen.active()); assertFalse(service.features.screen.projectionForeground)
   // Explicit local fixture: never opens a socket or changes a real display.
   val screen = service.features.screen
   val sessionType = screen.javaClass.declaredClasses.first { it.simpleName == "Session" }
   val constructor = sessionType.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
   val fixture = constructor.newInstance(screen, ScreenRequest.parse(request().put("source","desktop").put("control",true)), TlsEndpoint("fixture.invalid",17400,javax.net.ssl.SSLContext.getDefault()))
   org.robolectric.util.ReflectionHelpers.setField(screen,"current",fixture)
   val bitmap = Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
   android.graphics.Canvas(bitmap).apply {
    drawColor(android.graphics.Color.rgb(22,45,80))
    val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 42f }
    drawText("Computer screen · Test fixture",100f,130f,paint)
   }
   org.robolectric.util.ReflectionHelpers.setField(screen,"bitmap",bitmap)
   org.robolectric.util.ReflectionHelpers.setField(screen,"state","streaming")
   org.robolectric.util.ReflectionHelpers.setField(screen,"inputEnabled",true)
   org.robolectric.util.ReflectionHelpers.setField(screen,"displayAvailable",true)
   org.robolectric.util.ReflectionHelpers.setField(screen,"displayOn",false)
   service.connection.ready = true
   val viewer = Robolectric.buildActivity(ScreenViewerActivity::class.java).setup()
   val activity = viewer.get()
   val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
   val keyboard = activity.findViewById<android.widget.ImageButton>(R.id.screen_keyboard_toggle)
   assertTrue(keyboard.isEnabled)
   assertEquals("Turn on laptop display",activity.findViewById<android.view.View>(R.id.screen_display_toggle).contentDescription)
   content.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,48,0,40)).setInsets(android.view.WindowInsets.Type.ime(),android.graphics.Insets.of(0,0,0,450)).build())
   assertEquals(450,content.paddingBottom)
   keyboard.performClick(); shadowOf(Looper.getMainLooper()).idle()
   assertTrue(activity.findViewById<RemoteScreenView>(R.id.computer_screen_view).keyboardVisible)
   keyboard.performClick(); assertFalse(activity.findViewById<RemoteScreenView>(R.id.computer_screen_view).keyboardVisible)
   content.dispatchApplyWindowInsets(android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,48,0,40)).build())
   content.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY)); content.layout(0,0,1080,1920)
   val rendered = Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888); content.draw(android.graphics.Canvas(rendered))
   val output = java.io.File("build/reports/unity-preview/screen-control-test.png"); output.parentFile!!.mkdirs()
   output.outputStream().use { assertTrue(rendered.compress(Bitmap.CompressFormat.PNG,100,it)) }; rendered.recycle()
   assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,activity.requestedOrientation)
   val rotate = activity.findViewById<android.widget.ImageButton>(R.id.screen_orientation_toggle)
   rotate.performClick()
   assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,activity.requestedOrientation)
   val landscape = android.content.res.Configuration(activity.resources.configuration).apply {
    orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
    screenWidthDp = 800; screenHeightDp = 400
   }
   viewer.configurationChange(landscape)
   assertSame(activity,viewer.get()); assertTrue(screen.active()); assertSame(bitmap,screen.bitmap)
   assertEquals("Use portrait",rotate.contentDescription)
   content.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY)); content.layout(0,0,1920,1080)
   val landscapeView = activity.findViewById<RemoteScreenView>(R.id.computer_screen_view)
   assertTrue(landscapeView.width > landscapeView.height)
   val landscapeBitmap = Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888); content.draw(android.graphics.Canvas(landscapeBitmap))
   java.io.File("build/reports/unity-preview/screen-control-landscape-test.png").outputStream().use {
    assertTrue(landscapeBitmap.compress(Bitmap.CompressFormat.PNG,100,it))
   }; landscapeBitmap.recycle()
   rotate.performClick()
   assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,activity.requestedOrientation)
   rotate.performLongClick()
   assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,activity.requestedOrientation)
   assertTrue(screen.active())
   service.connection.ready = false
   service.features.screen.stop("Disconnected test"); shadowOf(Looper.getMainLooper()).idle()
   assertFalse(keyboard.isEnabled)
   viewer.pause().stop().destroy()
   bitmap.recycle()
   assertNull(service.features.screen.bitmap)
  } finally { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
 }
}
