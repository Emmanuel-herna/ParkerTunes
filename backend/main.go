package main

import (
	"encoding/json"
	"context"

	"google.golang.org/api/idtoken"
	"crypto/rand"
	"encoding/hex"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxMembers = 5
	maxUpcoming = 25
	heartbeat = 5 * time.Second
	grace = 45 * time.Second
)

type Track struct {
	VideoID string `json:"videoId"`
	Title string `json:"title"`
	Artist string `json:"artist"`
	ThumbnailURL string `json:"thumbnailUrl"`
	DurationMS int64 `json:"durationMs"`
	FromAutoplay bool `json:"fromAutoplay"`
}
type Member struct {
	UserID string `json:"userId"`
	DeviceID string `json:"deviceId"`
	DisplayName string `json:"displayName"`
	AvatarURL string `json:"avatarUrl,omitempty"`
	Token string `json:"-"`
	Host bool `json:"host"`
	Connected bool `json:"connected"`
	LastSeen time.Time `json:"-"`
	conn *websocket.Conn
	send chan []byte
}
type Playback struct {
	Seq int64 `json:"seq"`
	Track *Track `json:"track"`
	PositionMS int64 `json:"positionMs"`
	AnchorMS int64 `json:"anchorMs"`
	IsPlaying bool `json:"isPlaying"`
	QueueSeq int64 `json:"queueSeq"`
	QueueIndex int `json:"queueIndex"`
	QueueLength int `json:"queueLength"`
	StartedBy string `json:"startedBy,omitempty"`
	StartedByName string `json:"startedByName,omitempty"`
	UpdatedBy string `json:"updatedBy,omitempty"`
}
type Queue struct { Seq int64 `json:"seq"`; Index int `json:"index"`; Items []Track `json:"items"` }
type Party struct {
	mu sync.Mutex
	Code string `json:"code"`
	Members map[string]*Member `json:"members"`
	Playback Playback `json:"playback"`
	Queue Queue `json:"queue"`
	HostOnlyControl bool `json:"hostOnlyControl"`
	Created time.Time `json:"-"`
	EmptySince time.Time `json:"-"`
}
type Server struct { mu sync.Mutex; parties map[string]*Party; upgrader websocket.Upgrader; verifyGoogle func(context.Context,string)(*idtoken.Payload,error) }
type Identity struct { IDToken string `json:"idToken"`; UserID string `json:"-"`; DeviceID string `json:"deviceId"`; DisplayName string `json:"displayName"`; AvatarURL string `json:"-"` }
type envelope struct { Type string `json:"type"`; ClientMS int64 `json:"clientMs,omitempty"`; Action string `json:"action,omitempty"`; PositionMS int64 `json:"positionMs,omitempty"`; IsPlaying bool `json:"isPlaying,omitempty"`; Track *Track `json:"track,omitempty"`; Queue []Track `json:"queue,omitempty"`; QueueIndex int `json:"queueIndex,omitempty"`; Tracks []Track `json:"tracks,omitempty"`; VideoID string `json:"videoId,omitempty"`; FromIndex int `json:"fromIndex,omitempty"`; ToIndex int `json:"toIndex,omitempty"`; PlayNext bool `json:"playNext,omitempty"`; Enabled bool `json:"enabled,omitempty"` }
func nowMS() int64 { return time.Now().UnixMilli() }
func token() string { b:=make([]byte,32); if _,err:=rand.Read(b); err!=nil { panic(err) }; return hex.EncodeToString(b) }
func code() string { const alphabet="ABCDEFGHJKMNPQRSTUVWXYZ23456789"; b:=make([]byte,6); if _,err:=rand.Read(b); err!=nil { panic(err) }; for i:=range b { b[i]=alphabet[int(b[i])%len(alphabet)] }; return string(b) }
func normalize(s string) string { s=strings.ToUpper(strings.TrimSpace(s)); s=strings.ReplaceAll(s," ",""); s=strings.NewReplacer("O","0","I","1","L","1").Replace(s); return s }
func (s *Server) readIdentity(w http.ResponseWriter, r *http.Request) (Identity, bool) {
	var input Identity
	if json.NewDecoder(http.MaxBytesReader(w,r.Body,8192)).Decode(&input)!=nil || strings.TrimSpace(input.IDToken)=="" || strings.TrimSpace(input.DeviceID)=="" {
		s.write(w,http.StatusUnauthorized,map[string]string{"error":"google_sign_in_required"})
		return Identity{},false
	}
	verify:=s.verifyGoogle
	if verify==nil {
		audience:=strings.TrimSpace(os.Getenv("GOOGLE_WEB_CLIENT_ID"))
		if audience=="" {
			s.write(w,http.StatusServiceUnavailable,map[string]string{"error":"google_auth_not_configured"})
			return Identity{},false
		}
		verify=func(ctx context.Context,token string)(*idtoken.Payload,error){ return idtoken.Validate(ctx,token,audience) }
	}
	payload,err:=verify(r.Context(),input.IDToken)
	if err!=nil || payload==nil || strings.TrimSpace(payload.Subject)=="" {
		s.write(w,http.StatusUnauthorized,map[string]string{"error":"invalid_google_id_token"})
		return Identity{},false
	}
	email,_:=payload.Claims["email"].(string)
	verified,_:=payload.Claims["email_verified"].(bool)
	if email=="" || !verified {
		s.write(w,http.StatusUnauthorized,map[string]string{"error":"verified_google_email_required"})
		return Identity{},false
	}
	name,_:=payload.Claims["name"].(string)
	if strings.TrimSpace(name)=="" { name=strings.TrimSpace(strings.Split(email,"@")[0]) }
	picture,_:=payload.Claims["picture"].(string)
	input.UserID=payload.Subject
	input.DisplayName=name
	input.AvatarURL=picture
	return input,true
}
func (s *Server) write(w http.ResponseWriter, status int, v any) { w.Header().Set("Content-Type","application/json"); w.WriteHeader(status); _=json.NewEncoder(w).Encode(v) }
func (s *Server) party(code string) *Party { s.mu.Lock(); defer s.mu.Unlock(); return s.parties[normalize(code)] }
func (p *Party) snapshot() map[string]any { p.mu.Lock(); defer p.mu.Unlock(); return p.snapshotLocked() }
func (p *Party) snapshotLocked() map[string]any { members:=make([]map[string]any,0,len(p.Members)); for _,m:=range p.Members { members=append(members,map[string]any{"userId":m.UserID,"deviceId":m.DeviceID,"displayName":m.DisplayName,"avatarUrl":m.AvatarURL,"host":m.Host,"connected":m.Connected}) }; return map[string]any{"code":p.Code,"members":members,"maxMembers":maxMembers,"hostOnlyControl":p.HostOnlyControl,"playback":p.Playback,"queue":p.Queue} }
func (s *Server) create(w http.ResponseWriter,r *http.Request) { i,ok:=s.readIdentity(w,r); if !ok { return }; s.mu.Lock(); defer s.mu.Unlock(); if len(s.parties)>=50 { s.write(w,503,map[string]string{"error":"party_limit"}); return }; c:=code(); for s.parties[c]!=nil { c=code() }; m:=&Member{UserID:i.UserID,DeviceID:i.DeviceID,DisplayName:i.DisplayName,AvatarURL:i.AvatarURL,Token:token(),Host:true,LastSeen:time.Now()}; p:=&Party{Code:c,Members:map[string]*Member{i.DeviceID:m},Created:time.Now(),Queue:Queue{Items:[]Track{}}}; p.Playback.AnchorMS=nowMS(); s.parties[c]=p; s.write(w,201,map[string]any{"code":c,"token":m.Token,"you":map[string]any{"userId":m.UserID,"deviceId":m.DeviceID,"displayName":m.DisplayName,"host":true},"party":p.snapshot()}) }
func (s *Server) join(w http.ResponseWriter,r *http.Request,c string) { i,ok:=s.readIdentity(w,r); if !ok { return }; p:=s.party(c); if p==nil { s.write(w,404,map[string]string{"error":"not_found"}); return }; p.mu.Lock(); defer p.mu.Unlock(); if existing:=p.Members[i.DeviceID]; existing!=nil { existing.Token=token(); existing.DisplayName=i.DisplayName; existing.AvatarURL=i.AvatarURL; existing.UserID=i.UserID; m:=existing; s.write(w,200,map[string]any{"code":p.Code,"token":m.Token,"you":map[string]any{"deviceId":m.DeviceID,"displayName":m.DisplayName,"host":m.Host},"party":p.snapshotLocked()}); return }; if len(p.Members)>=maxMembers { s.write(w,409,map[string]string{"error":"full"}); return }; m:=&Member{UserID:i.UserID,DeviceID:i.DeviceID,DisplayName:i.DisplayName,AvatarURL:i.AvatarURL,Token:token(),LastSeen:time.Now()}; p.Members[i.DeviceID]=m; s.broadcastLocked(p,map[string]any{"type":"members","members":p.snapshotLocked()["members"],"maxMembers":maxMembers,"hostOnlyControl":p.HostOnlyControl,"serverMs":nowMS()}); s.write(w,200,map[string]any{"code":p.Code,"token":m.Token,"you":map[string]any{"deviceId":m.DeviceID,"displayName":m.DisplayName,"host":false},"party":p.snapshotLocked()}) }
func (s *Server) authenticate(r *http.Request,p *Party) (*Member,bool) { auth:=strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer ")); p.mu.Lock(); defer p.mu.Unlock(); for _,m:=range p.Members { if auth!="" && m.Token==auth { return m,true } }; return nil,false }
func (s *Server) serve(w http.ResponseWriter,r *http.Request) { if strings.HasPrefix(r.URL.Path,"/ws/parties/") { c:=normalize(strings.TrimPrefix(r.URL.Path,"/ws/parties/")); p:=s.party(c); if p==nil { http.NotFound(w,r); return }; s.socket(w,r,p); return }; if r.URL.Path=="/healthz" { s.write(w,200,map[string]string{"status":"ok"}); return }; if r.URL.Path=="/api/time" { s.write(w,200,map[string]int64{"serverMs":nowMS()}); return }; if r.URL.Path=="/api/parties" && r.Method=="POST" { s.create(w,r); return }; if strings.HasPrefix(r.URL.Path,"/api/parties/") { parts:=strings.Split(strings.Trim(r.URL.Path,"/"),"/"); if len(parts)>=3 { c:=normalize(parts[2]); p:=s.party(c); if len(parts)==4 && parts[3]=="join" && r.Method=="POST" { s.join(w,r,c); return }; if p==nil { s.write(w,404,map[string]string{"error":"not_found"}); return }; if r.Method=="GET" { if _,ok:=s.authenticate(r,p); !ok { s.write(w,401,map[string]string{"error":"unauthorized"}); return }; s.write(w,200,p.snapshot()); return }; if len(parts)==4 && parts[3]=="leave" && r.Method=="POST" { m,ok:=s.authenticate(r,p); if !ok { s.write(w,401,map[string]string{"error":"unauthorized"}); return }; p.mu.Lock(); delete(p.Members,m.DeviceID); if m.conn!=nil { _=m.conn.Close() }; if len(p.Members)>0 { for _,next:=range p.Members { next.Host=true; break } }; p.mu.Unlock(); s.write(w,200,map[string]bool{"ok":true}); return }; if len(parts)==4 && parts[3]=="ws" { s.socket(w,r,p); return } } }; http.NotFound(w,r) }
func (s *Server) broadcastLocked(p *Party,v any) { b,e:=json.Marshal(v); if e!=nil{return}; for _,m:=range p.Members { if m.conn!=nil { select { case m.send<-b: default: } } } }
func (s *Server) sendStateLocked(p *Party) { p.Playback.QueueSeq=p.Queue.Seq; p.Playback.QueueIndex=p.Queue.Index; p.Playback.QueueLength=len(p.Queue.Items); s.broadcastLocked(p,map[string]any{"type":"state","playback":p.Playback,"serverMs":nowMS()}) }
func (s *Server) socket(w http.ResponseWriter,r *http.Request,p *Party) { auth:=strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"),"Bearer ")); p.mu.Lock(); var member *Member; for _,m:=range p.Members { if auth!="" && m.Token==auth { member=m; break } }; p.mu.Unlock(); if member==nil { http.Error(w,"unauthorized",401); return }; c,e:=s.upgrader.Upgrade(w,r,nil); if e!=nil{return}; member.muConnect(p,c); defer member.disconnect(p); go member.writer(); member.sendWelcome(p); for { var msg envelope; if c.ReadJSON(&msg)!=nil{return}; member.handle(p,msg) } }
func (m *Member) muConnect(p *Party,c *websocket.Conn) { p.mu.Lock(); defer p.mu.Unlock(); if m.conn!=nil { _=m.conn.Close() }; m.conn=c; m.send=make(chan []byte,64); m.Connected=true; m.LastSeen=time.Now(); }
func (m *Member) writer() { for b:=range m.send { if m.conn==nil{return}; _=m.conn.SetWriteDeadline(time.Now().Add(8*time.Second)); if m.conn.WriteMessage(websocket.TextMessage,b)!=nil{return} } }
func (m *Member) disconnect(p *Party) { p.mu.Lock(); defer p.mu.Unlock(); if m.conn!=nil { _=m.conn.Close(); m.conn=nil }; if m.send!=nil { close(m.send); m.send=nil }; m.Connected=false; m.LastSeen=time.Now() }
func (m *Member) sendWelcome(p *Party) { p.mu.Lock(); defer p.mu.Unlock(); b,_:=json.Marshal(map[string]any{"type":"welcome","you":map[string]any{"deviceId":m.DeviceID,"displayName":m.DisplayName,"host":m.Host},"party":p.snapshotLocked(),"serverMs":nowMS()}); m.send<-b }
func (m *Member) handle(p *Party,msg envelope) { p.mu.Lock(); defer p.mu.Unlock(); m.LastSeen=time.Now(); if msg.Type=="ping" { m.sendJSON(map[string]any{"type":"pong","clientMs":msg.ClientMS,"serverMs":nowMS()}); return }; if msg.Type=="sync" { m.sendJSON(map[string]any{"type":"state","playback":p.Playback,"serverMs":nowMS()}); return }; if msg.Type=="syncQueue" { m.sendJSON(map[string]any{"type":"queue","queue":p.Queue,"serverMs":nowMS()}); return }; if msg.Type!="control" { return }; if p.HostOnlyControl && !m.Host { m.sendJSON(map[string]string{"type":"error","error":"host_only"}); return }; if msg.Action=="setHostOnlyControl" { if !m.Host { m.sendJSON(map[string]string{"type":"error","error":"host_only"}); return }; p.HostOnlyControl=msg.Enabled; sMembers:=make([]map[string]any,0,len(p.Members)); for _,x:=range p.Members { sMembers=append(sMembers,map[string]any{"deviceId":x.DeviceID,"displayName":x.DisplayName,"host":x.Host,"connected":x.Connected}) }; m.broadcast(p,map[string]any{"type":"members","members":sMembers,"maxMembers":maxMembers,"hostOnlyControl":p.HostOnlyControl,"serverMs":nowMS()}); return }; now:=nowMS(); switch msg.Action { case "play": p.Playback.PositionMS=msg.PositionMS; p.Playback.AnchorMS=now+350; p.Playback.IsPlaying=true
case "pause": if msg.PositionMS>0 {p.Playback.PositionMS=msg.PositionMS} else {p.Playback.PositionMS=positionAt(p.Playback,now)}; p.Playback.AnchorMS=now; p.Playback.IsPlaying=false
case "seek": p.Playback.PositionMS=msg.PositionMS; p.Playback.AnchorMS=now+350
case "setTrack":
if msg.Track!=nil {
	currentMatches:=p.Queue.Index>=0 && p.Queue.Index<len(p.Queue.Items) && p.Queue.Items[p.Queue.Index].VideoID==msg.Track.VideoID
	found:=-1
	for i:=p.Queue.Index+1;i<len(p.Queue.Items);i++ { if p.Queue.Items[i].VideoID==msg.Track.VideoID { found=i; break } }
	if currentMatches { } else if found>=0 { p.Queue.Index=found } else {
		upcoming:=[]Track{}
		if p.Queue.Index+1<len(p.Queue.Items) { upcoming=append(upcoming,p.Queue.Items[p.Queue.Index+1:]...) }
		p.Queue.Items=append([]Track{*msg.Track},upcoming...)
		p.Queue.Index=0
	}
	p.Queue.Seq++
}
p.Playback.Track=msg.Track; p.Playback.PositionMS=msg.PositionMS; p.Playback.AnchorMS=now+350; p.Playback.IsPlaying=msg.IsPlaying; p.Playback.StartedBy=m.DeviceID; p.Playback.StartedByName=m.DisplayName
case "setQueue": if len(msg.Queue)>maxUpcoming+1 {m.sendJSON(map[string]string{"type":"error","error":"queue_full"});return}; p.Queue.Items=append([]Track{},msg.Queue...); p.Queue.Index=msg.QueueIndex; p.Queue.Seq++
case "queueAdd": for _,t:=range msg.Tracks { if len(p.Queue.Items)>=maxUpcoming+1 {break}; if t.VideoID=="" {continue}; if t.FromAutoplay {duplicate:=false; for i:=p.Queue.Index+1;i<len(p.Queue.Items);i++ {if p.Queue.Items[i].VideoID==t.VideoID {duplicate=true}}; if duplicate {continue} }; at:=len(p.Queue.Items); if msg.PlayNext {at=p.Queue.Index+1}; if at<0 {at=0}; if at>len(p.Queue.Items){at=len(p.Queue.Items)}; p.Queue.Items=append(p.Queue.Items,Track{}); copy(p.Queue.Items[at+1:],p.Queue.Items[at:]); p.Queue.Items[at]=t }; p.Queue.Seq++
case "queueRemove": for i:=p.Queue.Index+1;i<len(p.Queue.Items);i++ {if p.Queue.Items[i].VideoID==msg.VideoID {p.Queue.Items=append(p.Queue.Items[:i],p.Queue.Items[i+1:]...);break}}; p.Queue.Seq++
case "queueClear": if p.Queue.Index+1<len(p.Queue.Items) {p.Queue.Items=p.Queue.Items[:p.Queue.Index+1]}; p.Queue.Seq++
case "queueMove": from:=msg.FromIndex; for i:=p.Queue.Index+1;i<len(p.Queue.Items);i++ {if msg.VideoID!=""&&p.Queue.Items[i].VideoID==msg.VideoID {from=i;break}}; to:=msg.ToIndex; if from>p.Queue.Index&&from<len(p.Queue.Items)&&to>p.Queue.Index&&to<len(p.Queue.Items) {v:=p.Queue.Items[from]; p.Queue.Items=append(p.Queue.Items[:from],p.Queue.Items[from+1:]...); if to>=len(p.Queue.Items){to=len(p.Queue.Items)-1}; p.Queue.Items=append(p.Queue.Items,Track{}); copy(p.Queue.Items[to+1:],p.Queue.Items[to:]); p.Queue.Items[to]=v}; p.Queue.Seq++
case "next": if p.Queue.Index+1<len(p.Queue.Items) {p.Queue.Index++;p.Playback.Track=&p.Queue.Items[p.Queue.Index];p.Playback.PositionMS=0;p.Playback.AnchorMS=now+350;p.Playback.IsPlaying=true;p.Playback.StartedBy=m.DeviceID;p.Playback.StartedByName=m.DisplayName}
case "previous": if p.Queue.Index>0 {p.Queue.Index--;p.Playback.Track=&p.Queue.Items[p.Queue.Index];p.Playback.PositionMS=0;p.Playback.AnchorMS=now+350;p.Playback.IsPlaying=true;p.Playback.StartedBy=m.DeviceID;p.Playback.StartedByName=m.DisplayName}
default:return }; p.Playback.Seq++; p.Playback.UpdatedBy=m.DeviceID; if strings.HasPrefix(msg.Action,"queue")||msg.Action=="setQueue"||msg.Action=="setTrack" { m.broadcast(p,map[string]any{"type":"queue","queue":p.Queue,"serverMs":nowMS()}) }; m.broadcast(p,map[string]any{"type":"state","playback":p.Playback,"serverMs":nowMS()}) }
func positionAt(s Playback,now int64) int64 { if !s.IsPlaying||now<=s.AnchorMS{return s.PositionMS}; return s.PositionMS+now-s.AnchorMS }
func (m *Member) sendJSON(v any) { b,e:=json.Marshal(v); if e==nil&&m.send!=nil {select{case m.send<-b:default:}} }
func (m *Member) broadcast(p *Party,v any) { b,e:=json.Marshal(v); if e!=nil{return}; for _,x:=range p.Members {if x.conn!=nil {select{case x.send<-b:default:}}} }
func main() { s:=&Server{parties:map[string]*Party{},upgrader:websocket.Upgrader{CheckOrigin:func(r *http.Request)bool{return r.Header.Get("Origin")==""||strings.Contains(os.Getenv("JAM_ALLOWED_ORIGINS"),r.Header.Get("Origin"))}}}; mux:=http.NewServeMux(); mux.HandleFunc("/",s.serve); go func(){ t:=time.NewTicker(heartbeat); for range t.C { s.mu.Lock(); for code,p:=range s.parties { p.mu.Lock(); now:=time.Now(); for id,m:=range p.Members { if !m.Connected && now.Sub(m.LastSeen)>grace { delete(p.Members,id) } }; hasHost:=false; for _,m:=range p.Members { if m.Host {hasHost=true;break} }; if !hasHost { for _,m:=range p.Members {m.Host=true;break} }; if len(p.Members)==0 { if p.EmptySince.IsZero(){p.EmptySince=now}; if now.Sub(p.EmptySince)>2*time.Minute {delete(s.parties,code);p.mu.Unlock();continue} } else {p.EmptySince=time.Time{}}; if now.Sub(p.Created)>12*time.Hour {delete(s.parties,code);p.mu.Unlock();continue}; s.sendStateLocked(p); p.mu.Unlock() }; s.mu.Unlock() } }(); addr:=":"+os.Getenv("PORT"); if addr==":" {addr=":8000"}; log.Fatal(http.ListenAndServe(addr,mux)) }

