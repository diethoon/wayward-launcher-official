# Wayward FLUX Image API

Cloudflare Worker endpoint for Wayward Launcher / WML image generation.

## Model

`@cf/black-forest-labs/flux-1-schnell`

## Cloudflare setup

1. In Cloudflare, connect the `diethoon/wayward-launcher-official` GitHub repository to Workers Builds.
2. Set the build root directory to `services/cloudflare-flux-worker`.
3. Use the default build command (`npm install`) and deploy command (`npm run deploy`), or configure Wrangler directly.
4. The included `wrangler.jsonc` declares the Workers AI binding as `env.AI`.
5. In the Worker settings, add an `API_KEY` secret before exposing the endpoint publicly. The launcher must send it as `Authorization: Bearer <API_KEY>` if configured.

## API

- `GET /` — health and model metadata.
- `POST /generate` — returns raw PNG bytes.

Example request:

```http
POST /generate
Content-Type: application/json
Authorization: Bearer YOUR_API_KEY

{"prompt":"A cozy fantasy village at sunset","width":1024,"height":1024}
```

A successful response is `200 image/png`. Errors are JSON.

## Local development

```bash
npm install
npx wrangler dev
```

## Security notes

- Do not commit Cloudflare API tokens or the `API_KEY` value.
- Configure `API_KEY` with `wrangler secret put API_KEY` or in the Cloudflare dashboard.
- Restrict production usage with an API key and Cloudflare rate limiting / abuse controls.
