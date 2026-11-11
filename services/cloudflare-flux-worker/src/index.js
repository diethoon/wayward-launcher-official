const MODEL = "@cf/black-forest-labs/flux-1-schnell";

const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-headers": "content-type,authorization",
  "access-control-max-age": "86400"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      ...corsHeaders,
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}

function authorized(request, env) {
  return Boolean(env.API_KEY) &&
    request.headers.get("authorization") === `Bearer ${env.API_KEY}`;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (request.method === "GET" && url.pathname === "/") {
      return json({
        service: "Wayward FLUX Image API",
        status: "ok",
        model: MODEL,
        generate: "POST /generate",
        authRequired: true,
        apiKeyConfigured: Boolean(env.API_KEY)
      });
    }

    if (request.method !== "POST" || url.pathname !== "/generate") {
      return json({ error: "Not found" }, 404);
    }

    if (!env.API_KEY) {
      return json({ error: "API_KEY secret is not configured" }, 503);
    }
    if (!authorized(request, env)) {
      return json({ error: "Unauthorized" }, 401);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Request body must be valid JSON" }, 400);
    }

    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    if (!prompt) return json({ error: "prompt is required" }, 400);
    if (prompt.length > 2048) {
      return json({ error: "prompt must be 2048 characters or fewer" }, 413);
    }

    try {
      const result = await env.AI.run(MODEL, {
        prompt,
        steps: 4
      });

      if (!result || typeof result.image !== "string" || !result.image) {
        throw new Error("Workers AI returned no base64 image");
      }

      const binary = atob(result.image);
      const imageBytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));

      const headers = new Headers(corsHeaders);
      headers.set("content-type", "image/jpeg");
      headers.set("cache-control", "no-store");
      headers.set("x-wayward-model", MODEL);
      return new Response(imageBytes, { status: 200, headers });
    } catch (error) {
      return json({
        error: "Image generation failed",
        detail: error instanceof Error ? error.message : String(error)
      }, 502);
    }
  }
};
