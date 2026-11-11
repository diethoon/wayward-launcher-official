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
  // Configure API_KEY as a Worker secret before public use.
  if (!env.API_KEY) return true;
  return request.headers.get("authorization") === `Bearer ${env.API_KEY}`;
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
        authRequired: Boolean(env.API_KEY)
      });
    }

    if (request.method !== "POST" || url.pathname !== "/generate") {
      return json({ error: "Not found" }, 404);
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
    if (prompt.length > 4000) {
      return json({ error: "prompt must be 4000 characters or fewer" }, 413);
    }

    const width = Number.isInteger(body.width) ? body.width : 1024;
    const height = Number.isInteger(body.height) ? body.height : 1024;
    if (width < 256 || width > 1440 || height < 256 || height > 1440) {
      return json({ error: "width and height must be between 256 and 1440" }, 400);
    }

    try {
      const image = await env.AI.run(MODEL, {
        prompt,
        width,
        height,
        steps: 4
      });

      const headers = new Headers(corsHeaders);
      headers.set("content-type", "image/png");
      headers.set("cache-control", "no-store");
      headers.set("x-wayward-model", MODEL);
      return new Response(image, { status: 200, headers });
    } catch (error) {
      return json({
        error: "Image generation failed",
        detail: error instanceof Error ? error.message : String(error)
      }, 502);
    }
  }
};
