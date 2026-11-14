import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../app/src/main/assets/imagegen-studio-bridge.js", import.meta.url), "utf8");
const outgoing = [];
const runtime = { available: false, starting: false, ready: false, backendVersion: "" };
const persisted = {
  imageBackendMode: "custom",
  imageBackendUrl: "https://my-worker.workers.dev/generate",
  keepOtherSetting: "preserved"
};
const events = [];
const settingsStore = { getState: () => persisted };
const backend = { baseUrl: "https://my-worker.workers.dev", comfyui: true };
const window = {
  __WAYWARD_IMAGE_STUDIO_TEST__: true,
  location: { href: "https://localhost:42000/game", origin: "https://localhost:42000" },
  setTimeout() { return 1; },
  clearTimeout() {},
  localStorage: {
    getItem(key) {
      return key === "tavern-settings"
        ? JSON.stringify({ state: persisted, version: 3 })
        : null;
    }
  },
  WMLHost: {
    imageGenRuntimeStatus() { return JSON.stringify(runtime); },
    logImageProviderDiagnostic(name, details) { events.push({ name, details: JSON.parse(details) }); }
  },
  fetch(input, init) {
    outgoing.push({ input: typeof input === "string" ? input : input.url, init });
    return Promise.resolve(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } }));
  }
};
const context = {
  window, URL, Request, Response, Headers, Promise, Object, String, Boolean, Number,
  JSON, Error, WeakSet, Uint8Array, Math, Date, btoa, atob, setTimeout, clearTimeout
};
vm.runInNewContext(source, context, { filename: "imagegen-studio-bridge.js" });

const factory = {
  connect(_wmlApi, config) {
    assert.equal(config.id, "imagestudio");
    return {
      fn(name) {
        if (name === "settingsStore") return settingsStore;
        if (name === "imageBackendProbe") {
          return async (options, force) => {
            outgoing.push({ probe: true, options, force });
            return backend;
          };
        }
        if (name === "imageServerUrl") return path => "https://my-worker.workers.dev" + path;
        if (name === "imageBackend") return () => backend;
        return undefined;
      }
    };
  }
};
window.__waywardPauseSettingsUiFactory = factory;
const api = factory.connect({}, { id: "imagestudio" });

assert.equal(api.fn("settingsStore").getState().imageBackendMode, "off",
  "server-off must force the in-memory Image Studio mode to OFF");
assert.equal(api.fn("settingsStore").getState().imageBackendUrl, "",
  "server-off must hide saved external generation URLs");
assert.equal(persisted.imageBackendMode, "custom", "persisted game settings must remain untouched");
assert.equal(persisted.imageBackendUrl, "https://my-worker.workers.dev/generate");
assert.equal(api.fn("imageBackendProbe")().then ? true : false, true);
const offProbe = await api.fn("imageBackendProbe")();
assert.equal(offProbe.disabled, true);
assert.equal(outgoing.some(item => item.probe), false,
  "server-off must not probe a saved Cloudflare Worker");
assert.equal(api.fn("imageBackend")().disabled, true);
assert.equal(api.fn("imageServerUrl")("/api/batch/enqueue"), "http://127.0.0.1:8189/api/batch/enqueue");

const packFetch = await window.fetch("https://art.sintention.com/images/portrait.png");
assert.equal(packFetch.status, 200);
assert.equal(outgoing.at(-1).input, "https://localhost:42000/images/portrait.png",
  "server-off image fetches must resolve through the local image-pack path");

const blocked = await window.fetch("https://my-worker.workers.dev/generate", { method: "POST", body: "{}" });
assert.equal(blocked.status, 503, "server-off must block direct fetch to the saved generation endpoint");
const directNaiOff = await window.fetch("https://image.novelai.net/ai/generate-image", {
  method: "POST", body: "{}"
});
assert.equal(directNaiOff.status, 503, "server-off must block direct NovelAI generation requests");
assert.equal(outgoing.length, 1, "external generation endpoints must not receive requests while the server is OFF");

runtime.available = true;
runtime.ready = true;
runtime.backendVersion = "launcher-imagegen:0.8.4:novelai";
const active = api.fn("settingsStore").getState();
assert.equal(active.imageBackendMode, "custom");
assert.equal(active.imageBackendUrl, "http://127.0.0.1:8189");
const localProbe = api.fn("imageBackendProbe");
await localProbe();
assert.equal(outgoing.some(item => item.probe && item.options.url === "http://127.0.0.1:8189"), true);
assert.equal(api.fn("imageBackend")().baseUrl, "http://127.0.0.1:8189");
const rewritten = await window.fetch("https://my-worker.workers.dev/api/batch/enqueue", {
  method: "POST", headers: { "Content-Type": "application/json" }, body: "{}"
});
assert.equal(rewritten.status, 200);
assert.equal(outgoing.at(-1).input, "http://127.0.0.1:8189/api/batch/enqueue",
  "server-on must rewrite saved external generation hosts to the local managed server");
const generatedFetch = await window.fetch("https://art.sintention.com/images/portrait.png");
assert.equal(generatedFetch.status, 200);
assert.equal(outgoing.at(-1).input, "https://localhost:42000/images/__wayward_generated__/portrait.png",
  "server-on image fetches must never fall back to image packs or the original CDN");
const directNaiOn = await window.fetch("https://image.novelai.net/ai/generate-image", {
  method: "POST", body: "{}"
});
assert.equal(directNaiOn.status, 503, "game code must not bypass the managed server with direct NovelAI requests");
assert.equal(persisted.imageBackendUrl, "https://my-worker.workers.dev/generate",
  "the user's persisted settings must not be overwritten");
console.log("Image Studio strict image-pack/server mode test passed.");
