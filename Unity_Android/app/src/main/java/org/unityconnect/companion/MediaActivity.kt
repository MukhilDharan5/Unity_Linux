package org.unityconnect.companion

import android.os.Bundle
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

/** Controls metadata sources in place; selecting/rendering never issues commands. */
class MediaActivity : AdwaitaActivity() {
 private lateinit var ui: AdwaitaUi
 private lateinit var picker: Spinner
 private lateinit var title: TextView
 private lateinit var artist: TextView
 private lateinit var source: TextView
 private lateinit var time: TextView
 private lateinit var artwork: ImageView
 private lateinit var seek: SeekBar
 private lateinit var play: Button
 private lateinit var next: Button
 private lateinit var previous: Button
 private data class VolumeRow(val slider: SeekBar,val mute: Button,val level: TextView,val detail: TextView,var dragging: Boolean=false,var dragTarget: String?=null,var pending: Boolean=false)
 private val volumes=linkedMapOf<String,VolumeRow>()
 private var players=listOf<JSONObject>()
 private var selected: String?=null
 private var signature: List<String>?=null
 private var updating=false
 private var dragging: String?=null
 private var busy=false
 private var bitmap: Bitmap?=null
 private var artKey: String?=null
 private val changed: () -> Unit={ render() }
 private fun key(p: JSONObject)=p.optString("origin")+":"+p.optString("session")
 private fun current()=players.firstOrNull { key(it)==selected }
 override fun onCreate(saved: Bundle?) {
  super.onCreate(saved); setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC); ui=AdwaitaUi(this); selected=saved?.getString("player") ?: intent.data?.lastPathSegment?.takeIf { Protocol.validId(it) }?.let { "desktop:$it" }
  val page=contentPage("Media")
  val volumeCard=ui.section(page,"Device volume")
  for ((origin,name) in listOf("desktop" to "Laptop output volume","phone" to "Phone media volume")) {
   val box=ui.column().apply { setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(14)) }
   val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
   heading.addView(ui.label(name,15f,true),LinearLayout.LayoutParams(0,-2,1f))
   val level=ui.label("Unavailable",13f,secondary=true);heading.addView(level);box.addView(heading)
   val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
   val slider=ui.slider(name).apply { max=100;id=if (origin=="desktop") R.id.laptop_volume else R.id.phone_volume }
   val mute=ui.button("Mute") { muteVolume(origin) }.apply { id=if (origin=="desktop") R.id.laptop_mute else R.id.phone_mute }
   row.addView(slider,LinearLayout.LayoutParams(0,ui.dp(48),1f));row.addView(mute);box.addView(row)
   val detail=ui.label("",12f,secondary=true);box.addView(detail);ui.addRow(volumeCard,box)
   val volume=VolumeRow(slider,mute,level,detail);volumes[origin]=volume
   slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?,progress: Int,fromUser: Boolean) { if (fromUser) level.text="$progress%" }
    override fun onStartTrackingTouch(bar: SeekBar?) { volume.dragging=true;volume.dragTarget=soundState(origin).optString("output") }
    override fun onStopTrackingTouch(bar: SeekBar?) {
     val target=volume.dragTarget;volume.dragging=false;volume.dragTarget=null
     if (target==soundState(origin).optString("output")) soundControl(origin,"volume",JSONObject().put("volume",slider.progress))
     renderVolumes()
    }
   })
  }
  val selection=ui.section(page,"Players","Choose a player on your laptop or phone.")
  picker=Spinner(this).apply {
   id=R.id.media_player_picker; minimumHeight=ui.dp(56); contentDescription="Media player"
   backgroundTintList=null; background=ui.ripple(android.graphics.Color.TRANSPARENT,16)
   setPopupBackgroundDrawable(ui.shape(ui.surface,16,true))
  }; selection.addView(picker,LinearLayout.LayoutParams(-1,-2))
  picker.onItemSelectedListener=object: android.widget.AdapterView.OnItemSelectedListener {
   override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
   override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: View?,position: Int,id: Long) { if (!updating && position<players.size) { selected=key(players[position]); renderPlayer() } }
  }
  val card=ui.section(page,"Now playing").apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(16),ui.dp(20),ui.dp(16),ui.dp(20)) }
  source=ui.label("",13f,secondary=true); card.addView(source)
  artwork=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription="Album artwork" }; card.addView(artwork,LinearLayout.LayoutParams(ui.dp(192),ui.dp(192)).apply { topMargin=ui.dp(16); bottomMargin=ui.dp(16) })
  title=ui.label("Nothing playing",22f,true).apply { id=R.id.media_track_title; gravity=Gravity.CENTER }; card.addView(title)
  artist=ui.label("",14f,secondary=true).apply { gravity=Gravity.CENTER; setPadding(0,ui.dp(8),0,ui.dp(12)) }; card.addView(artist)
  seek=ui.slider("Playback position").apply { id=R.id.media_seek }; card.addView(seek,LinearLayout.LayoutParams(-1,ui.dp(48)))
  time=ui.label("",13f,secondary=true); card.addView(time)
  val controls=LinearLayout(this).apply { gravity=Gravity.CENTER; orientation=if (ui.compactActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
  previous=ui.button("Previous") { control("previous") }.apply { id=R.id.media_previous }
  play=ui.button("Play",true) { control(if (current()?.optBoolean("playing")==true) "pause" else "play") }.apply { id=R.id.media_play }
  next=ui.button("Next") { control("next") }.apply { id=R.id.media_next }
  for (button in listOf(previous,play,next)) controls.addView(button,LinearLayout.LayoutParams(if (ui.compactActions) -1 else -2,-2).apply { setMargins(ui.dp(4),ui.dp(8),ui.dp(4),0) })
  card.addView(controls)
  seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
   override fun onProgressChanged(bar: SeekBar?,progress: Int,fromUser: Boolean) {}
   override fun onStartTrackingTouch(bar: SeekBar?) { dragging=current()?.let { key(it)+":"+it.optString("track_id") } }
   override fun onStopTrackingTouch(bar: SeekBar?) {
    val p=current(); if (p!=null && dragging==key(p)+":"+p.optString("track_id")) control("seek",JSONObject().put("position",seek.progress.toLong()))
    dragging=null; renderPlayer()
   }
  })
  render()
 }
 private fun render() {
  val service=CompanionService.instance
  val arrays=listOf(service?.features?.media?.players(),service?.features?.desktopMedia?.players())
  players=arrays.flatMap { list -> (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) } }
  if (players.none { key(it)==selected }) selected=players.firstOrNull { it.optBoolean("playing") }?.let(::key) ?: players.firstOrNull()?.let(::key)
  val names=players.map { (if (it.optString("origin")=="desktop") "Laptop" else "Phone")+" · "+it.optString("app_name").ifBlank { it.optString("package") } }
  val model=players.mapIndexed { i,p -> key(p)+names[i] }
  updating=true
  if (model!=signature) { picker.adapter=ui.choices(if (names.isEmpty()) listOf("No active players") else names,chevron=true); signature=model }
  picker.isEnabled=players.isNotEmpty(); picker.setSelection(players.indexOfFirst { key(it)==selected }.coerceAtLeast(0)); updating=false
  renderPlayer();renderVolumes()
 }
 private fun soundState(origin: String): JSONObject {
  val sound=CompanionService.instance?.features?.sound
  return (if (origin=="desktop") sound?.desktopState() else sound?.phoneState()) ?: JSONObject()
 }
 private fun renderVolumes() {
  val service=CompanionService.instance
  for ((origin,row) in volumes) {
   val state=soundState(origin)
   val available=state.optBoolean("available") && state.optString("origin")==origin && service?.features?.sound?.capability()?.optString("status")=="available"
   row.slider.isEnabled=available && !row.pending;row.mute.isEnabled=available && !row.pending && !row.dragging
   if (!row.dragging && !row.pending) row.slider.progress=state.optInt("volume")
   if (!row.dragging) row.level.text=if (!state.has("volume")) "Unavailable" else if (state.optBoolean("boosted")) ">100%" else "${state.optInt("volume")}%"
   row.mute.text=if (state.optBoolean("muted")) "Unmute" else "Mute"
   row.detail.text=state.optString("error").ifBlank { state.optString("name").ifBlank { "Connect and enable Media and sound controls." } }
  }
 }
 private fun muteVolume(origin: String) { soundControl(origin,"mute",JSONObject().put("muted",!soundState(origin).optBoolean("muted"))) }
 private fun soundControl(origin: String,action: String,data: JSONObject) {
  val service=CompanionService.instance ?: return;val row=volumes[origin] ?: return
  if (row.pending || !soundState(origin).optBoolean("available")) return
  try {
   data.put("source",origin)
   if (origin=="desktop") {
    data.put("output",soundState(origin).getString("output"));row.pending=true;renderVolumes()
    service.connection.request("sound",action,data) { ok,error -> row.pending=false;if (!isDestroyed) { if (!ok) Toast.makeText(this,error,Toast.LENGTH_LONG).show();renderVolumes() } }
   } else service.features.sound.command(action,data)
  } catch (error: Exception) { row.pending=false;Toast.makeText(this,error.message ?: "Volume unavailable",Toast.LENGTH_LONG).show();renderVolumes() }
 }
 private fun renderPlayer() {
  if (!::title.isInitialized) return
  val p=current(); val desktop=p?.optString("origin")=="desktop"
  title.text=p?.optString("title")?.ifBlank { "Untitled media" } ?: "Nothing playing"
  artist.text=if (p==null) "Start playback on your laptop or phone." else listOf(p.optString("artist"),p.optString("album")).filter { it.isNotBlank() }.joinToString(" · ")
  source.text=if (p==null) (if (CompanionService.instance?.features?.sound?.capability()?.optString("status")=="available") "No active players" else "Enable Media and sound controls in Features.") else (if (desktop) "Laptop" else "Phone")+" · "+p.optString("app_name").ifBlank { p.optString("package") }
  val duration=p?.optInt("duration") ?: 0
  if (dragging==null) { seek.max=maxOf(1,duration); seek.progress=p?.optInt("position")?.coerceIn(0,seek.max) ?: 0 }
  fun stamp(ms: Int)="${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"
  time.text=stamp(p?.optInt("position") ?: 0)+" / "+if (duration>0) stamp(duration) else "—:—"
  play.text=if (p?.optBoolean("playing")==true) "Pause" else "Play"
  val service=CompanionService.instance
  val allowed=service!=null && (if (desktop) service.connection.ready && service.connection.desktopAvailable("desktop_media") && service.features.desktopMedia.capability().optString("status")=="available" else service.features.media.capability().optString("status")=="available")
  val actions=p?.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
  for ((button,action) in listOf(previous to "previous",next to "next",play to if (p?.optBoolean("playing")==true) "pause" else "play",seek to "seek")) button.isEnabled=allowed && !busy && action in actions && (action!="seek" || duration>0)
  val image=p?.optJSONObject("artwork"); val encoded=image?.optString("data")
  if (encoded!=artKey) {
   artwork.setImageDrawable(null); bitmap?.recycle(); bitmap=null; artKey=encoded
   if (image!=null) bitmap=MediaArtwork.decode(image)
   artwork.setImageBitmap(bitmap)
  }
  if (bitmap==null) artwork.setImageResource(R.drawable.ic_media)
 }
 private fun control(action: String,extra: JSONObject=JSONObject()) {
  if (busy) return
  val p=current() ?: return; val service=CompanionService.instance ?: return
  try {
   extra.put("session",p.getString("session"))
   if (p.getString("origin")=="desktop") {
    check(service.features.desktopMedia.capability().optString("status")=="available")
    if (action=="seek") extra.put("track_id",p.optString("track_id"))
    busy=true; renderPlayer()
    service.connection.request("desktop_media",action,extra) { ok,error -> busy=false; if (!ok) Toast.makeText(this,error,Toast.LENGTH_LONG).show(); if (!isDestroyed) render() }
   } else service.features.media.command(action,extra)
  } catch (error: Exception) { busy=false; Toast.makeText(this,error.message ?: "Control unavailable",Toast.LENGTH_LONG).show(); renderPlayer() }
 }
 override fun onStart() { super.onStart(); CompanionEvents.observe(changed); CompanionService.instance?.features?.media?.watch(); CompanionService.instance?.features?.sound?.watch(); render() }
 override fun onStop() { CompanionEvents.remove(changed); super.onStop() }
 override fun onSaveInstanceState(out: Bundle) { out.putString("player",selected); super.onSaveInstanceState(out) }
 override fun onDestroy() { artwork.setImageDrawable(null); bitmap?.recycle(); bitmap=null; super.onDestroy() }
}
