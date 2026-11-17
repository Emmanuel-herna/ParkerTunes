package com.parker.tunes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parker.tunes.data.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
fun ListenTogetherScreen(client:ListenTogetherClient,player:MediaController?,api:MusicApi,onBack:()->Unit) {
    val state by client.state.collectAsState()
    val scope= rememberCoroutineScope()
    val context=LocalContext.current
    var signingIn by remember { mutableStateOf(false) }
    var signInStage by remember { mutableStateOf("") }
    var signInError by remember { mutableStateOf<String?>(null) }
    var name by remember(state.displayName){mutableStateOf(state.displayName)}
    var code by remember(state.code){mutableStateOf(state.code)}
    var lastApplied by remember { mutableStateOf("") }

    LaunchedEffect(state.party?.playback?.seq,state.connected,player) {
        val playback=state.party?.playback ?: return@LaunchedEffect
        val track=playback.track ?: return@LaunchedEffect
        val active=player ?: return@LaunchedEffect
        if(!state.connected || playback.seq.toString()==lastApplied)return@LaunchedEffect
        lastApplied=playback.seq.toString()
        client.beginRemoteApply()
        val position=client.estimatedPosition(playback)
        if(active.currentMediaItem?.mediaId!=track.videoId) {
            runCatching {
                val stream=withContext(Dispatchers.IO) { api.getStreamUrl(track.videoId) }
                val metadata=MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                    .setArtworkUri(track.thumbnailUrl?.let(android.net.Uri::parse)).build()
                active.setMediaItem(MediaItem.Builder().setMediaId(track.videoId).setUri(stream).setMediaMetadata(metadata).build(),position)
                active.prepare()
                if(playback.isPlaying) active.play() else active.pause()
            }.onFailure { signInError=it.message ?: "Couldn't load the shared track." }
        } else {
            if(kotlin.math.abs(active.currentPosition-position)>1400L) active.seekTo(position)
            if(playback.isPlaying&&!active.isPlaying) active.play()
            if(!playback.isPlaying&&active.isPlaying) active.pause()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}
            Text("Listen Together",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        if(state.code.isBlank()) {
            Text("Listen in sync",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
            Text("Sign in with Google, then create a party or join one with a 6-character code.")
            Spacer(Modifier.height(12.dp))
            if(state.userUid.isBlank()) {
                Button(
                    onClick={
                        signInError=null
                        signInStage="Preparing Google sign-in…"
                        scope.launch {
                            signingIn=true
                            try {
                                withTimeout(30_000L) {
                                signInStage="Opening Google account chooser…"
                                val option=GetGoogleIdOption.Builder()
                                    .setServerClientId(context.getString(R.string.default_web_client_id))
                                    .setFilterByAuthorizedAccounts(false)
                                    .setAutoSelectEnabled(false)
                                    .build()
                                val request=GetCredentialRequest.Builder().addCredentialOption(option).build()
                                val result=CredentialManager.create(context).getCredential(context,request)
                                signInStage="Completing Firebase authentication…"
                                val credential=result.credential
                                if(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    val google=GoogleIdTokenCredential.createFrom(credential.data)
                                    client.signInWithGoogle(google.idToken,google.displayName.orEmpty(),google.id,google.profilePictureUri?.toString())
                                } else error("Google did not return a valid sign-in credential.")
                                }
                            } catch(e:Exception) { signInError="${signInStage}: ${e.message ?: "Google sign-in failed."}" }
                            finally { signingIn=false; signInStage="" }
                        }
                    },
                    enabled=!signingIn,
                    modifier=Modifier.fillMaxWidth()
                ) { Text(if(signingIn) "Signing in…" else "Continue with Google") }
                if(signingIn) Text(signInStage,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            } else {
                ListItem(
                    headlineContent={Text(state.googleName.ifBlank { state.displayName })},
                    supportingContent={Text(state.googleEmail)},
                    trailingContent={TextButton(onClick={scope.launch { client.signOutGoogle() }}){Text("Sign out")}}
                )
            }
            signInError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value=name,onValueChange={name=it.take(40)},label={Text("Your display name")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=code,onValueChange={code=it.uppercase().take(6)},label={Text("Party code")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Button(onClick={client.saveDisplayName(name);scope.launch{client.create()}},enabled=!state.loading&&state.userUid.isNotBlank()&&name.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                Text(if(state.loading) "Working…" else "Create party")
            }
            OutlinedButton(onClick={client.saveDisplayName(name);scope.launch{client.join(code)}},enabled=!state.loading&&state.userUid.isNotBlank()&&name.isNotBlank()&&code.length==6,modifier=Modifier.fillMaxWidth()) { Text("Join party") }
        } else {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Groups,null)
                Column(Modifier.weight(1f)) { Text("Party code",style=MaterialTheme.typography.labelMedium); Text(state.code,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) }
                AssistChip(onClick={client.sendSync()},label={Text(if(state.connected) "Connected" else "Connecting…")})
            }
            Text("Share this code with up to four other devices.")
            Spacer(Modifier.height(14.dp))
            state.party?.playback?.let { p ->
                Text(p.track?.title ?: "Nothing playing",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(p.track?.artist.orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Started by " + (p.startedByName ?: "a party member"),style=MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FilledIconButton(onClick={if(p.isPlaying)client.control("pause",client.estimatedPosition(p)) else client.control("play",client.estimatedPosition(p))}) { Icon(if(p.isPlaying)Icons.Default.Pause else Icons.Default.PlayArrow,null) }
                    FilledIconButton(onClick={client.control("next")}) { Icon(Icons.Default.SkipNext,null) }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Members",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text((state.party?.members?.size ?: 0).toString()+"/5") }
            LazyColumn(Modifier.weight(1f)) {
                items(state.party?.members.orEmpty()) { member ->
                    ListItem(headlineContent={Text(member.displayName)},supportingContent={Text(if(member.host) "Host" else if(member.connected) "Connected" else "Offline")})
                }
                item { Text("Shared queue",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp)) }
                items(state.party?.let { it.queue.drop((it.queueIndex+1).coerceAtLeast(0)) }.orEmpty()) { queued ->
                    ListItem(headlineContent={Text(queued.title)},supportingContent={Text(queued.artist)},trailingContent={IconButton(onClick={client.control("queueRemove",videoId=queued.videoId)}){Icon(Icons.Default.Close,"Remove from shared queue")}})
                }
            }
            if(state.isHost) state.party?.let { party ->
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Host-only controls"); Spacer(Modifier.weight(1f)); Switch(checked=party.hostOnlyControl,onCheckedChange={client.control("setHostOnlyControl",enabled=it)}) }
            }
            OutlinedButton(onClick=client::leave,modifier=Modifier.fillMaxWidth()) { Text("Leave party") }
        }
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=10.dp)) }
    }
}
