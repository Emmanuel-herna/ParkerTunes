# Plyra Listen Together Go service

This directory contains a standalone Go WebSocket/HTTP implementation for Listen Together. It authenticates Google ID tokens, keeps party membership/playback/queue state in process memory, and lets each client resolve its own audio stream.

**Important:** the current Android app is not connected to this Go service. The Android implementation currently uses Firebase Authentication and Firebase Realtime Database directly (see `app/src/main/java/com/parker/tunes/data/ListenTogetherClient.kt`). Deploying this Go service alone will not change the transport used by the installed Android app. The Android client must be migrated to the Go HTTP/WebSocket protocol before this service becomes its active backend.

## Deploy the standalone service with Render

1. Push this repository to GitHub.
2. In Render, choose **New → Blueprint** and select this repository. Render reads the root `render.yaml`.
3. Configure the `GOOGLE_WEB_CLIENT_ID` environment variable with the Web OAuth client ID used by the Android sign-in flow.
4. Deploy the `parkertunes-listen-together` web service.
5. Check the service's `/healthz` endpoint.

The service listens on port 8000 by default. The Render Blueprint sets the health check path and starts the Go server from `backend/`.

## Run locally

```sh
cd backend
go mod tidy
go test ./...
go run .
```

## Current limitations

- Up to five devices per party.
- Party codes are six characters; member tokens are random bearer credentials.
- WebSocket authentication uses the `Authorization: Bearer <token>` header.
- Creating or joining a party requires a Google ID token. The service verifies the token signature, issuer, expiry, and Web OAuth audience, then uses Google's verified subject and profile claims.
- The server is single-instance and stores rooms in memory. A restart or idle free-tier sleep ends active rooms.
- Do not scale this service horizontally until shared room storage and pub/sub are implemented.
