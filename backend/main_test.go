package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gorilla/websocket"
)

func request(t *testing.T,s *Server,method,path string,body any,token string) *httptest.ResponseRecorder {
	t.Helper()
	var b bytes.Buffer
	if body!=nil { if err:=json.NewEncoder(&b).Encode(body); err!=nil {t.Fatal(err)} }
	r:=httptest.NewRequest(method,path,&b)
	if token!="" { r.Header.Set("Authorization","Bearer "+token) }
	w:=httptest.NewRecorder(); s.serve(w,r); return w
}
func newTestServer()*Server { return &Server{parties:map[string]*Party{},upgrader:websocket.Upgrader{}} }
func TestCreateJoinAndSnapshot(t *testing.T) {
	s:=newTestServer()
	host:=Identity{UserID:"u1",DeviceID:"d1",DisplayName:"Host"}
	created:=request(t,s,http.MethodPost,"/api/parties",host,"")
	if created.Code!=http.StatusCreated {t.Fatalf("create status=%d body=%s",created.Code,created.Body.String())}
	var result struct{Code string `json:"code"`;Token string `json:"token"`}
	if err:=json.Unmarshal(created.Body.Bytes(),&result);err!=nil {t.Fatal(err)}
	if len(result.Code)!=6||result.Token=="" {t.Fatal("missing party code or token")}
	joined:=request(t,s,http.MethodPost,"/api/parties/"+result.Code+"/join",Identity{UserID:"u2",DeviceID:"d2",DisplayName:"Guest"},"")
	if joined.Code!=http.StatusOK {t.Fatalf("join status=%d body=%s",joined.Code,joined.Body.String())}
	snapshot:=request(t,s,http.MethodGet,"/api/parties/"+result.Code,nil,result.Token)
	if snapshot.Code!=http.StatusOK {t.Fatalf("snapshot status=%d",snapshot.Code)}
	var party map[string]any
	if err:=json.Unmarshal(snapshot.Body.Bytes(),&party);err!=nil {t.Fatal(err)}
	if int(party["maxMembers"].(float64))!=5 {t.Fatal("wrong member capacity")}
}
func TestJoinRejectsSixthDevice(t *testing.T) {
	s:=newTestServer()
	created:=request(t,s,http.MethodPost,"/api/parties",Identity{UserID:"u0",DeviceID:"d0",DisplayName:"Host"},"")
	var result struct{Code string `json:"code"`}
	_ = json.Unmarshal(created.Body.Bytes(),&result)
	for i:=1;i<5;i++ { w:=request(t,s,http.MethodPost,"/api/parties/"+result.Code+"/join",Identity{UserID:"u",DeviceID:string(rune('a'+i)),DisplayName:"Guest"},""); if w.Code!=http.StatusOK {t.Fatalf("join %d status=%d",i,w.Code)} }
	last:=request(t,s,http.MethodPost,"/api/parties/"+result.Code+"/join",Identity{UserID:"u6",DeviceID:"d6",DisplayName:"Sixth"},"")
	if last.Code!=http.StatusConflict {t.Fatalf("expected 409, got %d",last.Code)}
}
func TestPositionAtUsesServerAnchor(t *testing.T) {
	p:=Playback{PositionMS:42000,AnchorMS:1000,IsPlaying:true}
	if got:=positionAt(p,2500);got!=43500 {t.Fatalf("got %d",got)}
	p.IsPlaying=false
	if got:=positionAt(p,9000);got!=42000 {t.Fatalf("paused position changed: %d",got)}
}
