# Cloudflare FLUX Worker deployment refresh

This documentation-only change is used to trigger the configured Production deployment from the `setup/cloudflare-flux-worker-20261112` branch.

Worker configuration: `wrangler.jsonc`
Worker entry point: `src/index.js`
Deploy command: `npx wrangler deploy --config services/cloudflare-flux-worker/wrangler.jsonc`

No runtime code, bindings, secrets, or game behavior are changed by this file.
