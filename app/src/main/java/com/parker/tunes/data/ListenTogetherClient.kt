package com.parker.tunes.data

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class JamTrack(val videoId:String,val title:String,val artist:String,val thumbnailUrl:String?,val durationMs:Long,val fromAutoplay:Boolean=false)
data class JamMember(val deviceId:String,val displayName:String,val host:Boolean,val connected:Boolean)
data class JamPlayback(val seq:Long,val track:JamTrack?,val positionMs:Long,val anchorMs:Long,val isPlaying:Boolean,val startedByName:String?)
data class JamSnapshot(val code:String,val members:List<JamMember>,val hostOnlyControl:Boolean,val playback:JamPlayback?,val queue:List<JamTrack>,val queueIndex:Int)
data class JamUiState(val serverUrl:String="https://",val displayName:String="",val code:String="",val token:String="",val connected:Boolean=false,val loading:Boolean=false,val error:String?=null,val party:JamSnapshot?=null)

class ListenTogetherClient(context:Context) {
    private val prefs=context.getSharedPreferences("listen_together",Context.MODE_PRIVATE)
    private val deviceId=prefs.getString("device_id",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id",it).apply() }
    private val userId=prefs.getString("user_id",null) ?: Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID).orEmpty().ifBlank { deviceId }
    private val http=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).build()
    private var socket:WebSocket?=null
    private val _state=MutableStateFlow(JamUiState(serverUrl=prefs.getString("server_url","")?:"",displayName=prefs.getString("display_name","")?:"",code=prefs.getString("party_code","")?:"",token=prefs.getString("party_token","")?:""))
    val state=_state.asStateFlow()
    private var clockOffsetMs=0L
    private var lastAppliedSeq=-1L
    fun saveSettings(url:String,name:String) { val clean=url.trim().trimEnd('/'); val safeName=name.trim(); prefs.edit().putString("server_url",clean).putString("display_name",safeName).apply(); _state.value=_state.value.copy(serverUrl=clean,displayName=safeName,error=null) }
    private fun identity()=JSONObject().put("userId",userId).put("deviceId",deviceId).put("displayName",_state.value.displayName)
    private fun base()=_state.value.serverUrl.trimEnd('/').also { require(it.startsWith("https://")||it.startsWith("http://")) { "Enter your HTTPS server address." } }
    suspend fun create()=partyRequest("POST", "${base()}/api/parties", identity())
    suspend fun join(code:String) { val normal=code.trim().uppercase().replace("O","0").replace("I","1").replace("L","1"); partyRequest("POST","${base()}/api/parties/$normal/join",identity()) }
    private suspend fun partyRequest(method:String,url:String,body:JSONObject) {
        _state.value=_state.value.copy(loading=true,error=null)
        try {
            val req=Request.Builder().url(url).method(method,body.toString().toRequestBody("application/json".toMediaType())).build()
            val response=http.newCall(req).execute(); val raw=response.body?.string().orEmpty()
            if(!response.isSuccessful) throw IllegalStateException(JSONObject(raw).optString("error","Request failed (${response.code})"))
            val json=JSONObject(raw); val code=json.optString("code"); val token=json.optString("token")
            prefs.edit().putString("party_code",code).putString("party_token",token).apply()
            _state.value=_state.value.copy(code=code,token=token,loading=false)
            parseParty(json.optJSONObject("party") ?: JSONObject())
            connect()
        } catch(e:Exception) { _state.value=_state.value.copy(loading=false,error=e.message?: "Couldn't connect to the party") }
    }
    fun connect() {
        val s=_state.value
        if(s.token.isBlank()||s.code.isBlank()) return
        socket?.close(1000,"reconnect")
        val url=base().replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/ws/parties/${s.code}"
        val request=Request.Builder().url(url).header("Authorization","Bearer ${s.token}").build()
        socket=http.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response) { _state.value=_state.value.copy(connected=true,error=null); ping() }
            override fun onMessage(webSocket:WebSocket,text:String) { runCatching { handleFrame(JSONObject(text)) }.onFailure { _state.value=_state.value.copy(error=it.message) } }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { _state.value=_state.value.copy(connected=false,error=t.message?: "Connection lost") }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { _state.value=_state.value.copy(connected=false) }
        })
    }
    private fun handleFrame(j:JSONObject) {
        when(j.optString("type")) {
            "welcome" -> { parseParty(j.optJSONObject("party")?:JSONObject()); send(JSONObject().put("type","sync")) }
            "pong" -> { val sent=j.optLong("clientMs"); val received=System.currentTimeMillis(); val sample=j.optLong("serverMs")-(sent+received)/2; clockOffsetMs=sample }
            "state" -> { val p=j.optJSONObject("playback")?:return; val seq=p.optLong("seq"); if(seq<=lastAppliedSeq)return; lastAppliedSeq=seq; val old=_state.value.party; _state.value=_state.value.copy(party=old?.copy(playback=JamPlayback(seq,parseTrack(p.optJSONObject("track")),p.optLong("positionMs"),p.optLong("anchorMs"),p.optBoolean("isPlaying"),p.optString("startedByName").takeIf{it.isNotBlank()}))) }
            "queue" -> { val q=j.optJSONObject("queue")?:return; val old=_state.value.party?:return; _state.value=_state.value.copy(party=old.copy(queue=parseTracks(q.optJSONArray("items")),queueIndex=q.optInt("index"))) }
            "members" -> { val old=_state.value.party?:return; val arr=j.optJSONArray("members")?:JSONArray(); val members=(0 until arr.length()).map { arr.getJSONObject(it).toMember() }; _state.value=_state.value.copy(party=old.copy(members=members,hostOnlyControl=j.optBoolean("hostOnlyControl"))) }
            "error" -> _state.value=_state.value.copy(error=j.optString("error"))
            "bye" -> leaveLocal()
        }
    }
    private fun parseParty(j:JSONObject) { val membersArray=j.optJSONArray("members")?:JSONArray(); val q=j.optJSONObject("queue")?:JSONObject(); val p=j.optJSONObject("playback"); val members=(0 until membersArray.length()).map{membersArray.getJSONObject(it).toMember()}; _state.value=_state.value.copy(party=JamSnapshot(j.optString("code",_state.value.code),members,j.optBoolean("hostOnlyControl"),p?.let{JamPlayback(it.optLong("seq"),parseTrack(it.optJSONObject("track")),it.optLong("positionMs"),it.optLong("anchorMs"),it.optBoolean("isPlaying"),it.optString("startedByName").takeIf{x->x.isNotBlank()})},parseTracks(q.optJSONArray("items")),q.optInt("index"))) }
    private fun JSONObject.toMember()=JamMember(optString("deviceId"),optString("displayName"),optBoolean("host"),optBoolean("connected"))
    private fun parseTrack(j:JSONObject?):JamTrack? { if(j==null)return null; val id=j.optString("videoId"); if(id.isBlank())return null; return JamTrack(id,j.optString("title"),j.optString("artist"),j.optString("thumbnailUrl").takeIf{it.isNotBlank()},j.optLong("durationMs"),j.optBoolean("fromAutoplay")) }
    private fun parseTracks(a:JSONArray?):List<JamTrack> { if(a==null)return emptyList(); return (0 until a.length()).mapNotNull{parseTrack(a.optJSONObject(it))} }
    fun control(action:String,positionMs:Long?=null,track:JamTrack?=null,isPlaying:Boolean?=null,enabled:Boolean?=null) { val j=JSONObject().put("type","control").put("action",action); positionMs?.let{j.put("positionMs",it)}; track?.let{j.put("track",JSONObject().put("videoId",it.videoId).put("title",it.title).put("artist",it.artist).put("thumbnailUrl",it.thumbnailUrl).put("durationMs",it.durationMs).put("fromAutoplay",it.fromAutoplay))}; isPlaying?.let{j.put("isPlaying",it)}; enabled?.let{j.put("enabled",it)}; send(j) }
    fun addToQueue(track:JamTrack,playNext:Boolean=false) { val t=JSONObject().put("videoId",track.videoId).put("title",track.title).put("artist",track.artist).put("thumbnailUrl",track.thumbnailUrl).put("durationMs",track.durationMs).put("fromAutoplay",track.fromAutoplay); send(JSONObject().put("type","control").put("action","queueAdd").put("tracks",JSONArray().put(t)).put("playNext",playNext)) }
    fun sendSync(){send(JSONObject().put("type","sync"))}
    private fun send(j:JSONObject){socket?.send(j.toString())}
    private fun ping(){send(JSONObject().put("type","ping").put("clientMs",System.currentTimeMillis()))}
    fun estimatedPosition(p:JamPlayback):Long { val now=System.currentTimeMillis()+clockOffsetMs; return if(p.isPlaying) p.positionMs+(now-p.anchorMs).coerceAtLeast(0L) else p.positionMs }
    fun leave(){ val s=_state.value; if(s.token.isNotBlank()&&s.code.isNotBlank()) { val req=Request.Builder().url("${s.serverUrl}/api/parties/${s.code}/leave").header("Authorization","Bearer ${s.token}").post(ByteArray(0).toRequestBody()).build(); http.newCall(req).enqueue(object:Callback{override fun onFailure(call:Call,e:java.io.IOException){};override fun onResponse(call:Call,response:Response){response.close()}}) }; socket?.close(1000,"left"); leaveLocal() }
    private fun leaveLocal(){socket?.close(1000,"left");socket=null;prefs.edit().remove("party_code").remove("party_token").apply();_state.value=_state.value.copy(code="",token="",connected=false,party=null)}
    fun close(){socket?.close(1000,"screen closed");socket=null}
}
