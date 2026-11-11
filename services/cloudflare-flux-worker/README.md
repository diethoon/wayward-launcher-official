# Wayward FLUX Image API

Cloudflare Worker endpoint for Wayward Launcher / WML image generation.

## Model

`@cf/black-forest-labs/flux-1-schnell`

## Cloudflare Workers Builds setup

1. Connect the `diethoon/wayward-launcher-official` GitHub repository.
2. Set the production branch to `setup/cloudflare-flux-worker-20261112`. The Worker source files are not present on `main`.
3. Set root directory to the repository root (`/`).
4. Leave Build command empty.
5. Set Deploy command to `npx wrangler deploy --config services/cloudflare-flux-worker/wrangler.jsonc`.
6. The Worker name is `wayward-launcher-official`, matching the existing Cloudflare Worker.
7. The Wrangler config declares the Workers AI binding as `env.AI`.

## Required runtime secret

Before enabling generation, open the Worker in Cloudflare:

1. Go to **Settings → Variables and Secrets → Add**.
2. Choose **Secret**.
3. Set the variable name to `API_KEY`.
4. Enter a long random value generated and stored privately.
5. Save and deploy the updated Worker.

The `/generate` endpoint returns `503` until `API_KEY` is configured. It then requires `Authorization: Bearer <API_KEY>`. Do not put the Cloudflare account API token in the launcher; this Worker key is a separate credential.

## API

- `GET /` — health and model metadata.
- `POST /generate` — returns raw JPEG bytes.

A successful generation response is `200 image/jpeg`. Errors are JSON.

## Local development

```bash
npm install
npx wrangler dev
```

## Security notes

- Never commit Cloudflare account API tokens or the `API_KEY` value.
- Store `API_KEY` as a Worker secret, not a plaintext build variable.
- Add Cloudflare rate limiting / abuse controls before distributing the endpoint broadly.
