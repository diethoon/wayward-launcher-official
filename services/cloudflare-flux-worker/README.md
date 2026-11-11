# Wayward FLUX Image API

Cloudflare Worker endpoint for Wayward Launcher / WML image generation.

## Model

`@cf/black-forest-labs/flux-1-schnell`

## Cloudflare Workers Builds setup

1. Connect the `diethoon/wayward-launcher-official` GitHub repository.
2. Select production branch `setup/cloudflare-flux-worker-20261112`.
3. Set root directory to `services/cloudflare-flux-worker`.
4. Leave Build command empty (this project has no separate compile step).
5. Set Deploy command to `npx wrangler deploy`.
6. Keep the Worker name as `wayward-flux-image-api`, matching `wrangler.jsonc`.
7. The included `wrangler.jsonc` declares the Workers AI binding as `env.AI`.

## Required runtime secret

Before enabling generation, open the Worker in Cloudflare:

1. Go to **Settings → Variables and Secrets → Add**.
2. Choose **Secret**.
3. Set the variable name to `API_KEY`.
4. Enter a long random value generated and stored privately.
5. Save and deploy the updated Worker.

The `/generate` endpoint deliberately returns `503` until `API_KEY` is configured. It then requires `Authorization: Bearer <API_KEY>`. Do not put the Cloudflare account API token in the launcher; this Worker key is a separate credential.

## API

- `GET /` — health and model metadata (does not generate an image).
- `POST /generate` — returns raw JPEG bytes.

Example request:

```http
POST /generate
Content-Type: application/json
Authorization: Bearer YOUR_API_KEY

{"prompt":"A cozy fantasy village at sunset"}
```

A successful response is `200 image/jpeg`. Errors are JSON.

## Local development

```bash
npm install
npx wrangler dev
```

## Security notes

- Never commit Cloudflare account API tokens or the `API_KEY` value.
- The `API_KEY` must be configured as a Worker secret, not a plaintext build variable.
- Add Cloudflare rate limiting / abuse controls before distributing the endpoint broadly.
