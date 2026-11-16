(() => {
  "use strict";
  const host = window.WMLHost;
  if (!host || typeof host.chooseImagePackFolder !== "function" ||
      window.__waywardNativeImagePackPickerInstalled) return;
  window.__waywardNativeImagePackPickerInstalled = true;

  let nextId = 0;
  const pending = new Map();
  const rootByHandle = new WeakMap();

  function domError(name, message) {
    try { return new DOMException(message || name, name); }
    catch (_) {
      const error = new Error(message || name);
      error.name = name;
      return error;
    }
  }

  function parseResult(raw) {
    let result;
    try { result = typeof raw === "string" ? JSON.parse(raw) : raw; }
    catch (_) { throw domError("NotReadableError", "The native image-pack bridge returned invalid data."); }
    if (!result || result.ok !== true) {
      throw domError(result && result.name || "NotReadableError",
        result && result.message || "The image-pack operation failed.");
    }
    return result;
  }

  function nativeRequest() {
    const id = "imagepack-" + (++nextId) + "-" + Math.floor(Math.random() * 1000000000);
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      try { host.chooseImagePackFolder(id); }
      catch (error) {
        pending.delete(id);
        reject(domError("NotAllowedError", String(error && error.message || error)));
      }
    });
  }

  Object.defineProperty(window, "__waywardNativeImagePackPickerResolve", {
    configurable: false,
    enumerable: false,
    value(id, result) {
      const request = pending.get(String(id));
      if (!request) return;
      pending.delete(String(id));
      if (result && result.ok === true) request.resolve(result);
      else request.reject(domError(result && result.name || "AbortError",
        result && result.message || "Image-pack folder selection was cancelled."));
    }
  });

  function childName(value) {
    const name = String(value || "");
    if (!name || name === "." || name === ".." || name.indexOf("/") >= 0 ||
        name.indexOf("\\") >= 0 || name.indexOf("\0") >= 0) {
      throw domError("TypeError", "A single relative file or folder name is required.");
    }
    return name;
  }

  function nativeJson(method, ...args) {
    if (typeof host[method] !== "function") {
      throw domError("NotSupportedError", "The native image-pack operation is unavailable.");
    }
    return parseResult(host[method](...args));
  }

  function listEntries(path) {
    return nativeJson("listImagePackDirectory", path).entries || [];
  }

  function makeDirectory(path, name, permissions) {
    const handle = {
      kind: "directory",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "directory" &&
          rootByHandle.get(other) === rootByHandle.get(handle) && other.__path === path;
      },
      async queryPermission(options) {
        return (!options || options.mode !== "readwrite" || permissions.writable)
          ? "granted" : "denied";
      },
      async requestPermission(options) {
        return this.queryPermission(options);
      },
      async getDirectoryHandle(name, options) {
        name = childName(name);
        const childPath = path ? path + "/" + name : name;
        const entry = listEntries(path).find(item => item.name === name);
        if (!entry) {
          if (!options || options.create !== true) {
            throw domError("NotFoundError", "Directory not found: " + name);
          }
          if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
          nativeJson("createImagePackDirectory", childPath);
          return makeDirectory(childPath, name, permissions);
        }
        if (entry.kind !== "directory") throw domError("TypeMismatchError", "The selected entry is not a directory.");
        return makeDirectory(childPath, name, permissions);
      },
      async getFileHandle(name, options) {
        name = childName(name);
        const childPath = path ? path + "/" + name : name;
        const entry = listEntries(path).find(item => item.name === name);
        if (!entry) {
          if (!options || options.create !== true) throw domError("NotFoundError", "File not found: " + name);
          if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
          nativeJson("writeImagePackFile", childPath, "application/octet-stream", "");
        } else if (entry.kind !== "file") {
          throw domError("TypeMismatchError", "The selected entry is not a file.");
        }
        return makeFile(childPath, name, entry || {}, permissions);
      },
      async removeEntry(name) {
        name = childName(name);
        if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
        nativeJson("deleteImagePackEntry", path ? path + "/" + name : name);
      },
      async *entries() {
        const children = listEntries(path);
        for (const entry of children) {
          const childPath = path ? path + "/" + entry.name : entry.name;
          const child = entry.kind === "directory"
            ? makeDirectory(childPath, entry.name, permissions)
            : makeFile(childPath, entry.name, entry, permissions);
          yield [entry.name, child];
        }
      },
      async *values() {
        for await (const pair of this.entries()) yield pair[1];
      },
      async *keys() {
        for await (const pair of this.entries()) yield pair[0];
      },
      async resolve(other) {
        if (!other || rootByHandle.get(other) !== rootByHandle.get(handle)) return null;
        const target = String(other.__path || "");
        if (target === path) return [];
        const prefix = path ? path + "/" : "";
        if (!target.startsWith(prefix)) return null;
        return target.slice(prefix.length).split("/");
      }
    };
    Object.defineProperty(handle, "__path", { value: path });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  function makeFile(path, name, metadata, permissions) {
    const handle = {
      kind: "file",
      name,
      async isSameEntry(other) {
        return !!other && other.kind === "file" &&
          rootByHandle.get(other) === rootByHandle.get(handle) && other.__path === path;
      },
      async getFile() {
        const payload = nativeJson("readImagePackFile", path);
        const binary = atob(payload.data || "");
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        return new File([bytes], name, {
          type: payload.mimeType || metadata.mimeType || "application/octet-stream",
          lastModified: Number(payload.lastModified || metadata.lastModified || 0)
        });
      },
      async createWritable() {
        if (!permissions.writable) throw domError("NotAllowedError", "The selected tree is read-only.");
        let buffer = new Uint8Array(0);
        let position = 0;
        let closed = false;
        return {
          async write(value) {
            if (closed) throw domError("InvalidStateError", "The writable stream is closed.");
            if (value && typeof value === "object" && value.type === "seek") {
              position = Math.max(0, Number(value.position) || 0);
              return;
            }
            if (value && typeof value === "object" && value.type === "truncate") {
              const size = Math.max(0, Number(value.size) || 0);
              if (size > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
              const resized = new Uint8Array(size);
              resized.set(buffer.subarray(0, size));
              buffer = resized;
              position = Math.min(position, size);
              return;
            }
            if (value && typeof value === "object" && value.type === "write") {
              if (Number.isFinite(value.position)) position = Math.max(0, value.position);
              value = value.data;
            }
            let bytes;
            if (value instanceof Blob) bytes = new Uint8Array(await value.arrayBuffer());
            else if (typeof value === "string") bytes = new TextEncoder().encode(value);
            else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
            else if (ArrayBuffer.isView(value)) {
              bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
            } else {
              throw new TypeError("Unsupported image-pack writable data.");
            }
            const required = position + bytes.length;
            if (required > 32 * 1024 * 1024) throw domError("QuotaExceededError", "The image file exceeds 32 MiB.");
            const next = new Uint8Array(Math.max(buffer.length, required));
            next.set(buffer);
            next.set(bytes, position);
            buffer = next;
            position = required;
          },
          async close() {
            if (closed) return;
            let binary = "";
            for (let offset = 0; offset < buffer.length; offset += 0x8000) {
              binary += String.fromCharCode(...buffer.subarray(offset, offset + 0x8000));
            }
            nativeJson("writeImagePackFile", path,
              metadata.mimeType || "application/octet-stream", btoa(binary));
            closed = true;
          },
          async abort() {
            closed = true;
            buffer = new Uint8Array(0);
          }
        };
      }
    };
    Object.defineProperty(handle, "__path", { value: path });
    rootByHandle.set(handle, permissions.root);
    return handle;
  }

  const showDirectoryPicker = async function(options) {
    options = options || {};
    const mode = options.mode || "readwrite";
    if (mode !== "read" && mode !== "readwrite") throw new TypeError("Invalid directory picker mode.");
    const selection = await nativeRequest();
    if (mode === "readwrite" && selection.writable !== true) {
      throw domError("NotAllowedError", "Android did not grant write access to the selected folder.");
    }
    const permissions = { root: {}, writable: selection.writable === true };
    return makeDirectory("", selection.name || "Image packs", permissions);
  };

  try {
    Object.defineProperty(window, "showDirectoryPicker", {
      configurable: true,
      enumerable: true,
      writable: true,
      value: showDirectoryPicker
    });
  } catch (_) {
    window.showDirectoryPicker = showDirectoryPicker;
  }

  window.__waywardReadPersistedImagePackRoot = function() {
    const info = nativeJson("getImagePackFolderInfo");
    const permissions = { root: {}, writable: info.writable === true };
    return makeDirectory("", info.name || "Image packs", permissions);
  };
})();