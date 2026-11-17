package com.parker.tunes.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.SecureRandom

data class JamTrack(val videoId:String,val title:String,val artist:String,val thumbnailUrl:String?,val durationMs:Long,val fromAutoplay:Boolean=false)
data class JamMember(val deviceId:String,val displayName:String,val host:Boolean,val connected:Boolean)
data class JamPlayback(val seq:Long,val track:JamTrack?,val positionMs:Long,val anchorMs:Long,val isPlaying:Boolean,val startedByName:String?)
data class JamSnapshot(val code:String,val members:List<JamMember>,val hostOnlyControl:Boolean,val playback:JamPlayback?,val queue:List<JamTrack>,val queueIndex:Int)
data class JamUiState(val displayName:String="",val googleName:String="",val googleEmail:String="",val googleAvatarUrl:String?=null,val userUid:String="",val code:String="",val token:String="",val isHost:Boolean=false,val connected:Boolean=false,val loading:Boolean=false,val error:String?=null,val party:JamSnapshot?=null)

class ListenTogetherClient(context:Context) {
    private val appContext=context.applicationContext
    companion object {
        private const val URL="https://parkerjnr-5a13a-default-rtdb.firebaseio.com"
        private const val CODE_CHARS="ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
    private val prefs=context.getSharedPreferences("listen_together",Context.MODE_PRIVATE)
    private val auth=FirebaseAuth.getInstance()
    private val db=FirebaseDatabase.getInstance(URL).reference
    private val _state=MutableStateFlow(JamUiState(
        displayName=prefs.getString("display_name","") ?: "",
        googleName=prefs.getString("google_name","") ?: "",
        googleEmail=prefs.getString("google_email","") ?: "",
        googleAvatarUrl=prefs.getString("google_avatar",null),
        userUid=auth.currentUser?.uid.orEmpty()))
    val state=_state.asStateFlow()
    @Volatile private var suppressUntil=0L
    private var roomListener:ValueEventListener?=null
    private var membersListener:ValueEventListener?=null
    private var playbackListener:ValueEventListener?=null
    private var queueListener:ValueEventListener?=null
    private var onlineListener:ValueEventListener?=null
    private var roomData:DataSnapshot?=null
    private var membersData:DataSnapshot?=null
    private var playbackData:DataSnapshot?=null
    private var queueData:DataSnapshot?=null

    fun beginRemoteApply(){suppressUntil=System.currentTimeMillis()+1800L}
    fun canBroadcastLocal()=System.currentTimeMillis()>suppressUntil

    suspend fun signInWithGoogle(idToken:String,name:String,email:String,avatar:String?) {
        val result=auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken,null)).await()
        val user=result.user ?: error("Firebase sign-in did not return a user.")
        val safe=name.ifBlank { user.displayName ?: email.substringBefore("@").ifBlank { "ParkerTunes listener" } }.take(40)
        prefs.edit().putString("google_name",safe).putString("google_email",email).putString("google_avatar",avatar).putString("display_name",safe).apply()
        _state.value=_state.value.copy(userUid=user.uid,googleName=safe,googleEmail=email,googleAvatarUrl=avatar,displayName=safe,error=null)
    }

    suspend fun signOutGoogle() {
        runCatching { CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest()) }
        leave()
        auth.signOut()
        prefs.edit().remove("google_name").remove("google_email").remove("google_avatar").apply()
        _state.value=JamUiState()
    }

    fun saveDisplayName(value:String) {
        val name=value.trim().take(40)
        prefs.edit().putString("display_name",name).apply()
        _state.value=_state.value.copy(displayName=name,error=null)
    }

    suspend fun create() {
        val user=auth.currentUser ?: return fail("Sign in with Google first.")
        _state.value=_state.value.copy(loading=true,error=null)
        try {
            var code:String
            do { code=generateCode() } while(db.child("rooms").child(code).get().await().exists())
            val name=_state.value.displayName.ifBlank { user.displayName ?: "ParkerTunes listener" }.take(40)
            val now=System.currentTimeMillis()
            val updates=mapOf<String,Any?>(
                "rooms/"+code to mapOf("hostUid" to user.uid,"createdAt" to now,"hostOnlyControl" to false),
                "members/"+code+"/"+user.uid to mapOf("uid" to user.uid,"displayName" to name,"joinedAt" to now,"connected" to true),
                "playback/"+code to mapOf("seq" to 0L,"track" to null,"positionMs" to 0L,"anchorMs" to now,"isPlaying" to false),
                "queues/"+code to mapOf("items" to emptyMap<String,Any>(),"index" to -1))
            db.updateChildren(updates).await()
            saveDisplayName(name)
            enterRoom(code,user.uid)
        } catch(e:Exception) { fail(e.message ?: "Couldn't create the party.") }
    }

    suspend fun join(raw:String) {
        val user=auth.currentUser ?: return fail("Sign in with Google first.")
        val code=raw.trim().uppercase().replace("O","0").replace("I","1").replace("L","1")
        if(code.length!=6) return fail("Enter the 6-character party code.")
        _state.value=_state.value.copy(loading=true,error=null)
        try {
            val room=db.child("rooms").child(code).get().await()
            if(!room.exists()) error("That party code wasn't found.")
            val name=_state.value.displayName.ifBlank { user.displayName ?: "ParkerTunes listener" }.take(40)
            val ok=reserve(db.child("members").child(code),user.uid,name)
            if(!ok) error("This party is full (maximum 5 devices).")
            saveDisplayName(name)
            enterRoom(code,user.uid)
        } catch(e:Exception) { fail(e.message ?: "Couldn't join the party.") }
    }

    private suspend fun reserve(ref:DatabaseReference,uid:String,name:String):Boolean {
        val current=ref.get().await()
        if(!current.child(uid).exists() && current.childrenCount>=5L) return false
        ref.child(uid).setValue(mapOf("uid" to uid,"displayName" to name,
            "joinedAt" to System.currentTimeMillis(),"connected" to true)).await()
        return true
    }

    private fun enterRoom(code:String,uid:String) {
        detach()
        prefs.edit().putString("party_code",code).apply()
        _state.value=_state.value.copy(code=code,token=uid,isHost=false,loading=false,error=null,connected=false)
        val room=db.child("rooms").child(code)
        val members=db.child("members").child(code)
        val playback=db.child("playback").child(code)
        val queue=db.child("queues").child(code)
        roomListener=listener({roomData=it; refresh()}).also { room.addValueEventListener(it) }
        membersListener=listener({membersData=it; refresh()}).also { members.addValueEventListener(it) }
        playbackListener=listener({playbackData=it; refresh()}).also { playback.addValueEventListener(it) }
        queueListener=listener({queueData=it; refresh()}).also { queue.addValueEventListener(it) }
        onlineListener=listener({ online ->
            val connected=online.getValue(Boolean::class.java)==true
            _state.value=_state.value.copy(connected=connected)
            if(connected) members.child(uid).child("connected").setValue(true)
        }).also { db.child(".info/connected").addValueEventListener(it) }
    }

    private fun listener(onData:(DataSnapshot)->Unit)=object:ValueEventListener {
        override fun onDataChange(snapshot:DataSnapshot)=onData(snapshot)
        override fun onCancelled(error:DatabaseError){fail(error.message)}
    }

    private fun refresh() {
        val room=roomData ?: return
        val members=membersData ?: return
        val hostUid=room.child("hostUid").getValue(String::class.java).orEmpty()
        val list=members.children.map { m ->
            JamMember(m.key.orEmpty(),m.child("displayName").getValue(String::class.java).orEmpty(),
                m.key==hostUid,m.child("connected").getValue(Boolean::class.java) ?: false)
        }
        val p=playbackData
        val playback=p?.takeIf { it.exists() }?.let {
            JamPlayback(it.child("seq").getValue(Long::class.java) ?: 0L,parseTrack(it.child("track")),
                it.child("positionMs").getValue(Long::class.java) ?: 0L,
                it.child("anchorMs").getValue(Long::class.java) ?: 0L,
                it.child("isPlaying").getValue(Boolean::class.java) ?: false,
                it.child("startedByName").getValue(String::class.java))
        }
        val q=queueData
        val tracks=q?.child("items")?.children?.toList().orEmpty().sortedBy { it.key?.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { parseTrack(it) }
        val index=q?.child("index")?.getValue(Int::class.java) ?: -1
        _state.value=_state.value.copy(isHost=hostUid==_state.value.userUid,
            party=JamSnapshot(_state.value.code,list,room.child("hostOnlyControl").getValue(Boolean::class.java) ?: false,playback,tracks,index))
    }

    private fun parseTrack(s:DataSnapshot?):JamTrack? {
        if(s==null || !s.exists()) return null
        val id=s.child("videoId").getValue(String::class.java).orEmpty()
        if(id.isBlank()) return null
        return JamTrack(id,s.child("title").getValue(String::class.java).orEmpty(),s.child("artist").getValue(String::class.java).orEmpty(),
            s.child("thumbnailUrl").getValue(String::class.java),s.child("durationMs").getValue(Long::class.java) ?: 0L,
            s.child("fromAutoplay").getValue(Boolean::class.java) ?: false)
    }

    fun control(action:String,positionMs:Long?=null,track:JamTrack?=null,isPlaying:Boolean?=null,enabled:Boolean?=null,videoId:String?=null) {
        val code=_state.value.code
        if(code.isBlank()) return
        when(action) {
            "setTrack" -> if(track!=null) publish(track,positionMs ?: 0L,isPlaying ?: true)
            "play","pause","seek" -> {
                val old=_state.value.party?.playback ?: return
                val playing=isPlaying ?: (if(action=="play") true else if(action=="pause") false else old.isPlaying)
                publish(old.track,positionMs ?: estimatedPosition(old),playing)
            }
            "next","previous" -> {
                val party=_state.value.party ?: return
                val i=if(action=="next") party.queueIndex+1 else party.queueIndex-1
                if(i in party.queue.indices) {
                    db.child("queues").child(code).child("index").setValue(i)
                    publish(party.queue[i],0L,true)
                }
            }
            "setHostOnlyControl" -> if(_state.value.isHost && enabled!=null) db.child("rooms").child(code).child("hostOnlyControl").setValue(enabled)
            "queueRemove" -> {
                val party=_state.value.party ?: return
                writeQueue(party.queue.filterNot { it.videoId==videoId },party.queueIndex)
            }
        }
    }

    private fun publish(track:JamTrack?,position:Long,playing:Boolean) {
        val code=_state.value.code
        val oldSeq=_state.value.party?.playback?.seq ?: 0L
        val t=track?.let { mapOf("videoId" to it.videoId,"title" to it.title,"artist" to it.artist,
            "thumbnailUrl" to it.thumbnailUrl,"durationMs" to it.durationMs,"fromAutoplay" to it.fromAutoplay) }
        db.child("playback").child(code).setValue(mapOf("seq" to oldSeq+1L,"track" to t,
            "positionMs" to position.coerceAtLeast(0L),"anchorMs" to System.currentTimeMillis(),
            "isPlaying" to playing,"startedByName" to _state.value.displayName))
    }

    fun addToQueue(track:JamTrack,playNext:Boolean=false) {
        val p=_state.value.party ?: return
        val items=p.queue.toMutableList()
        val at=if(playNext) (p.queueIndex+1).coerceAtLeast(0).coerceAtMost(items.size) else items.size
        items.add(at,track)
        writeQueue(items,p.queueIndex)
    }

    private fun writeQueue(items:List<JamTrack>,index:Int) {
        val values=items.mapIndexed { i,t -> i.toString() to mapOf("videoId" to t.videoId,"title" to t.title,
            "artist" to t.artist,"thumbnailUrl" to t.thumbnailUrl,"durationMs" to t.durationMs,"fromAutoplay" to t.fromAutoplay) }.toMap()
        db.child("queues").child(_state.value.code).setValue(mapOf("items" to values,"index" to index.coerceAtMost(items.lastIndex)))
    }

    fun sendSync() {
        val s=_state.value
        if(s.code.isNotBlank()) db.child("members").child(s.code).child(s.userUid).child("connected").setValue(true)
    }

    fun estimatedPosition(p:JamPlayback)=if(p.isPlaying) p.positionMs+(System.currentTimeMillis()-p.anchorMs).coerceAtLeast(0L) else p.positionMs

    fun leave() {
        val s=_state.value
        if(s.code.isNotBlank() && s.userUid.isNotBlank()) {
            val others=s.party?.members.orEmpty().filter { it.deviceId!=s.userUid }
            if(s.isHost && others.isNotEmpty()) db.child("rooms").child(s.code).child("hostUid").setValue(others.first().deviceId)
            db.child("members").child(s.code).child(s.userUid).removeValue()
            if(others.isEmpty()) {
                db.child("rooms").child(s.code).removeValue()
                db.child("playback").child(s.code).removeValue()
                db.child("queues").child(s.code).removeValue()
            }
        }
        detach()
        prefs.edit().remove("party_code").apply()
        _state.value=_state.value.copy(code="",token="",isHost=false,connected=false,party=null,error=null)
    }

    private fun detach() {
        val code=_state.value.code
        roomListener?.let { db.child("rooms").child(code).removeEventListener(it) }
        membersListener?.let { db.child("members").child(code).removeEventListener(it) }
        playbackListener?.let { db.child("playback").child(code).removeEventListener(it) }
        queueListener?.let { db.child("queues").child(code).removeEventListener(it) }
        onlineListener?.let { db.child(".info/connected").removeEventListener(it) }
        roomListener=null;membersListener=null;playbackListener=null;queueListener=null;onlineListener=null
        roomData=null;membersData=null;playbackData=null;queueData=null
    }

    fun close(){detach()}
    private fun generateCode():String { val r=SecureRandom(); return buildString { repeat(6){append(CODE_CHARS[r.nextInt(CODE_CHARS.length)])} } }
    private fun fail(message:String){_state.value=_state.value.copy(loading=false,error=message)}
}
