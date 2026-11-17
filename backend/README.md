# ParkerTunes Listen Together backend

The Listen Together service is a separate Go WebSocket/HTTP service. It keeps party membership, the shared playback anchor and shared queue in memory. Each ParkerTunes device resolves and streams its own audio through the existing music source; the server never distributes stream URLs.

## Deploy with Render

1. Push this repository to GitHub.
2. In Render, choose **New → Blueprint** and select this repository. Render reads the root `render.yaml`.
3. Deploy the `parkertunes-listen-together` web service.
4. Copy its HTTPS address (for example, `https://parkertunes-listen-together.onrender.com`).
5. In ParkerTunes, open **Together**, enter that server address and a display name, then create a party. Other devices enter the same server address and six-character code.

The free Render instance may sleep when idle. When it sleeps or restarts, in-memory parties are lost. Use a continuously running instance for dependable sessions. Run one instance only: parties are held in that process's memory.

## Run locally

```sh
cd backend
go mod tidy
go test ./...
go run .
```

The service listens on port 8000 by default. Health check: `GET /healthz`.

## Notes

- Up to five devices per party.
- Party codes are six characters; member tokens are random bearer credentials.
- WebSocket authentication uses the `Authorization: Bearer <token>` header.
- Display names are supplied by each app installation. ParkerTunes currently has no Google sign-in, so the backend cannot verify the identity claim.
- The current backend is intentionally single-instance and in-memory. Persistent rooms and horizontal scaling require shared storage/pub-sub.
