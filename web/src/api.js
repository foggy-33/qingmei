const API_BASE = (import.meta.env.VITE_API_BASE || window.location.origin).replace(/\/$/, "");
const CAMPUS_SHARE_BASE = (import.meta.env.VITE_CAMPUS_SHARE_BASE || "https://campus-qm.upcshare.cn:8088").replace(/\/$/, "");
const PUBLIC_SHARE_BASE = (import.meta.env.VITE_PUBLIC_SHARE_BASE || "https://qm.upcshare.cn:8443").replace(/\/$/, "");
const AUTH_TOKEN_KEY = "qm_auth_token_v1";

function qs(params) {
  return new URLSearchParams(params).toString();
}

async function req(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  const token = getAuthToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers
  });
  if (!res.ok) {
    const message = await safeError(res);
    throw new Error(message);
  }
  return res.json();
}

export function getAuthToken() {
  return localStorage.getItem(AUTH_TOKEN_KEY) || "";
}

export function hasAuthToken() {
  return !!getAuthToken();
}

export function setAuthToken(token) {
  if (token) localStorage.setItem(AUTH_TOKEN_KEY, token);
}

export function clearAuthToken() {
  localStorage.removeItem(AUTH_TOKEN_KEY);
}

export function publicShareBase() {
  return PUBLIC_SHARE_BASE;
}

export function campusShareBase() {
  return CAMPUS_SHARE_BASE;
}

async function safeError(res) {
  try {
    const data = await res.json();
    return data.error || `Request failed: ${res.status}`;
  } catch {
    return `Request failed: ${res.status}`;
  }
}

export async function listAssets(limit = 24, offset = 0) {
  return req(`/api/v1/assets?${qs({ limit, offset })}`);
}

export async function uploadAsset(file, onProgress, onReady) {
  const form = new FormData();
  form.append("file", file);

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    if (typeof onReady === "function") {
      onReady(() => xhr.abort());
    }
    xhr.open("POST", `${API_BASE}/api/v1/assets/upload`);
    const token = getAuthToken();
    if (token) {
      xhr.setRequestHeader("Authorization", `Bearer ${token}`);
    }

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && typeof onProgress === "function") {
        onProgress({
          loaded: event.loaded,
          total: event.total,
          percent: Math.round((event.loaded / event.total) * 100)
        });
      }
    };

    xhr.onload = () => {
      try {
        const data = JSON.parse(xhr.responseText || "{}");
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(data);
        } else {
          reject(new Error(data.error || `Request failed: ${xhr.status}`));
        }
      } catch {
        reject(new Error(`Request failed: ${xhr.status}`));
      }
    };
    xhr.onerror = () => reject(new Error("上传失败"));
    xhr.onabort = () => reject(new Error("上传已取消"));
    xhr.send(form);
  });
}

export async function deleteAsset(assetId) {
  return req(`/api/v1/assets/${encodeURIComponent(assetId)}`, {
    method: "DELETE"
  });
}

export async function createShare(assetId, expiryHours = 24, annotations = []) {
  return req(`/api/v1/assets/${assetId}/share`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      expiry_hours: expiryHours,
      annotations_json: JSON.stringify(annotations || [])
    })
  });
}

export async function getShare(token) {
  return req(`/api/v1/public/shares/${encodeURIComponent(token)}`);
}

export async function updateShareAnnotations(token, annotations = []) {
  return req(`/api/v1/shares/${encodeURIComponent(token)}/annotations`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ annotations_json: JSON.stringify(annotations || []) })
  });
}

export async function getAdminOverview() {
  return req("/api/v1/admin/overview");
}

export async function setAdminUser(username, value) {
  return req(`/api/v1/admin/users/${encodeURIComponent(username)}/admin`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ value })
  });
}

export async function setBannedUser(username, value) {
  return req(`/api/v1/admin/users/${encodeURIComponent(username)}/banned`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ value })
  });
}

export async function registerUser(username, password) {
  const data = await req("/api/v1/auth/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ username, password })
  });
  setAuthToken(data.token);
  return data;
}

export async function loginUser(username, password) {
  const data = await req("/api/v1/auth/login", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ username, password })
  });
  setAuthToken(data.token);
  return data;
}

export async function getCurrentUser() {
  return req("/api/v1/auth/me");
}

export async function logoutUser() {
  try {
    await req("/api/v1/auth/logout", { method: "POST" });
  } finally {
    clearAuthToken();
  }
}

export function streamUrl(assetId) {
  const token = encodeURIComponent(getAuthToken());
  return `${API_BASE}/api/v1/assets/${assetId}/stream?access_token=${token}`;
}

export function adminStreamUrl(assetId) {
  const token = encodeURIComponent(getAuthToken());
  return `${API_BASE}/api/v1/admin/assets/${assetId}/stream?access_token=${token}`;
}

export function downloadUrl(assetId) {
  const token = encodeURIComponent(getAuthToken());
  return `${API_BASE}/api/v1/assets/${assetId}/download?access_token=${token}`;
}

export function shareStreamUrl(token) {
  return `${API_BASE}/s/${encodeURIComponent(token)}`;
}
