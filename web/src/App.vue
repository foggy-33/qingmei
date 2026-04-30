<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
  campusShareBase,
  clearAuthToken,
  createShare,
  deleteAsset,
  getCurrentUser,
  getShare,
  hasAuthToken,
  listAssets,
  loginUser,
  logoutUser,
  publicShareBase,
  registerUser,
  shareStreamUrl,
  streamUrl,
  updateShareAnnotations,
  uploadAsset
} from "./api";

const LS_PROJECTS = "qm_projects_v3";
const LS_META = "qm_asset_meta_v3";
const LS_ALIAS = "qm_asset_alias_v3";
const LS_ANNOTATIONS = "qm_annotations_v3";
const LS_SHARES = "qm_share_links_v1";

const authReady = ref(false);
const authLoading = ref(false);
const authMode = ref("login");
const authError = ref("");
const authForm = ref({ username: "", password: "" });
const currentUser = ref(null);

const assets = ref([]);
const loading = ref(false);
const uploading = ref(false);
const status = ref("");
const thumbnails = ref({});

const projects = ref([]);
const assetMeta = ref({});
const assetAlias = ref({});
const annotationMap = ref({});
const selectedProjectId = ref("");
const activeTab = ref("files");

const view = ref("home");
const selectedAssetId = ref("");
const noteText = ref("");
const replyDrafts = ref({});
const videoRef = ref(null);
const pausedAtSec = ref(null);
const pauseDetected = ref(false);
const shareLinks = ref({});
const expiryHours = ref(24);
const savedShares = ref({});
const uploadProgress = ref({ visible: false, name: "", percent: 0, speed: "0 KB/s" });
const shareMode = ref(false);
const shareToken = ref("");
const sharedAsset = ref(null);
const sharedAnnotations = ref([]);
const shareLoading = ref(false);

const dragging = ref(false);
const fileInput = ref(null);
const selectedKeys = ref([]);
const menu = ref({ visible: false, x: 0, y: 0, type: "blank", key: "" });

function uid() {
  return Math.random().toString(36).slice(2, 10);
}

function now() {
  return new Date().toISOString();
}

function defaultProject() {
  return { id: "default", name: "默认项目", createdAt: now(), folders: [] };
}

function userStorageKey(base) {
  return `${base}:${currentUser.value?.username || "guest"}`;
}

function routeShareToken() {
  return new URLSearchParams(window.location.search).get("share") || "";
}

function routeCampusBase() {
  return new URLSearchParams(window.location.search).get("campus") || "";
}

async function maybeRedirectToCampusShare() {
  const token = routeShareToken();
  const campusBase = (routeCampusBase() || campusShareBase()).replace(/\/$/, "");
  if (!token || !campusBase || window.location.origin === campusBase) return false;

  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 1200);
  try {
    const res = await fetch(`${campusBase}/api/v1/public/shares/${encodeURIComponent(token)}`, {
      method: "GET",
      signal: controller.signal
    });
    if (!res.ok) return false;
    const target = `${campusBase}${window.location.pathname}?share=${encodeURIComponent(token)}`;
    window.location.replace(target);
    return true;
  } catch {
    return false;
  } finally {
    window.clearTimeout(timer);
  }
}

function switchAuthMode(mode) {
  authMode.value = mode;
  authError.value = "";
}

async function submitAuth() {
  authLoading.value = true;
  authError.value = "";
  try {
    const username = authForm.value.username.trim();
    const password = authForm.value.password;
    const data = authMode.value === "register"
      ? await registerUser(username, password)
      : await loginUser(username, password);
    currentUser.value = data.user;
    authForm.value.password = "";
    loadLocalState();
    if (routeShareToken()) {
      await openSharedReview(routeShareToken());
    } else {
      await loadAssets();
    }
  } catch (err) {
    authError.value = err.message || "操作失败";
  } finally {
    authLoading.value = false;
  }
}

async function bootstrapAuth() {
  authReady.value = false;
  if (await maybeRedirectToCampusShare()) return;
  if (!hasAuthToken()) {
    currentUser.value = null;
    authReady.value = true;
    return;
  }
  try {
    const data = await getCurrentUser();
    currentUser.value = data.user;
    loadLocalState();
    if (routeShareToken()) {
      await openSharedReview(routeShareToken());
    } else {
      await loadAssets();
    }
  } catch {
    clearAuthToken();
    currentUser.value = null;
  } finally {
    authReady.value = true;
  }
}

async function onLogout() {
  await logoutUser();
  currentUser.value = null;
  assets.value = [];
  status.value = "";
  view.value = "home";
  selectedAssetId.value = "";
  selectedKeys.value = [];
  shareMode.value = false;
  shareToken.value = "";
  sharedAsset.value = null;
  sharedAnnotations.value = [];
}

function normalizeAnnotation(note) {
  return {
    id: note.id || uid(),
    timeSec: Number(note.timeSec || 0),
    text: note.text || "",
    author: note.author || "未知用户",
    createdAt: note.createdAt || now(),
    replies: Array.isArray(note.replies) ? note.replies.map((r) => ({
      id: r.id || uid(),
      text: r.text || "",
      author: r.author || "未知用户",
      createdAt: r.createdAt || now()
    })) : []
  };
}

function parseAnnotationsJson(raw) {
  try {
    const parsed = JSON.parse(raw || "[]");
    return Array.isArray(parsed) ? parsed.map(normalizeAnnotation) : [];
  } catch {
    return [];
  }
}

async function openSharedReview(token) {
  shareLoading.value = true;
  status.value = "";
  try {
    const data = await getShare(token);
    shareMode.value = true;
    shareToken.value = token;
    sharedAsset.value = data.asset;
    sharedAnnotations.value = parseAnnotationsJson(data.annotations_json);
    selectedAssetId.value = "";
    view.value = "detail";
  } catch (err) {
    status.value = err.message || "分享链接加载失败";
  } finally {
    shareLoading.value = false;
  }
}

async function persistAnnotations() {
  if (shareMode.value) {
    const data = await updateShareAnnotations(shareToken.value, sharedAnnotations.value);
    sharedAnnotations.value = parseAnnotationsJson(data.annotations_json);
    return;
  }
  saveLocalState();
}

function loadLocalState() {
  try {
    const p = JSON.parse(localStorage.getItem(userStorageKey(LS_PROJECTS)) || "[]");
    const m = JSON.parse(localStorage.getItem(userStorageKey(LS_META)) || "{}");
    const a = JSON.parse(localStorage.getItem(userStorageKey(LS_ALIAS)) || "{}");
    const n = JSON.parse(localStorage.getItem(userStorageKey(LS_ANNOTATIONS)) || "{}");
    const s = JSON.parse(localStorage.getItem(userStorageKey(LS_SHARES)) || "{}");
    projects.value = Array.isArray(p) && p.length ? p : [defaultProject()];
    assetMeta.value = m && typeof m === "object" ? m : {};
    assetAlias.value = a && typeof a === "object" ? a : {};
    annotationMap.value = n && typeof n === "object"
      ? Object.fromEntries(Object.entries(n).map(([id, list]) => [id, Array.isArray(list) ? list.map(normalizeAnnotation) : []]))
      : {};
    savedShares.value = s && typeof s === "object" ? s : {};
  } catch {
    projects.value = [defaultProject()];
    assetMeta.value = {};
    assetAlias.value = {};
    annotationMap.value = {};
    savedShares.value = {};
  }

  if (!selectedProjectId.value || !projects.value.find((p) => p.id === selectedProjectId.value)) {
    selectedProjectId.value = projects.value[0].id;
  }
}

function saveLocalState() {
  if (!currentUser.value) return;
  localStorage.setItem(userStorageKey(LS_PROJECTS), JSON.stringify(projects.value));
  localStorage.setItem(userStorageKey(LS_META), JSON.stringify(assetMeta.value));
  localStorage.setItem(userStorageKey(LS_ALIAS), JSON.stringify(assetAlias.value));
  localStorage.setItem(userStorageKey(LS_ANNOTATIONS), JSON.stringify(annotationMap.value));
  localStorage.setItem(userStorageKey(LS_SHARES), JSON.stringify(savedShares.value));
}

function ensureAssetMapping() {
  if (!projects.value.length) projects.value = [defaultProject()];
  const validProjectIds = new Set(projects.value.map((p) => p.id));
  const ids = new Set(assets.value.map((a) => a.id));
  const nextMeta = { ...assetMeta.value };

  for (const a of assets.value) {
    if (!nextMeta[a.id]) {
      nextMeta[a.id] = { projectId: selectedProjectId.value || projects.value[0].id };
    }
    if (!validProjectIds.has(nextMeta[a.id].projectId)) {
      nextMeta[a.id].projectId = projects.value[0].id;
    }
  }
  for (const k of Object.keys(nextMeta)) {
    if (!ids.has(k)) delete nextMeta[k];
  }
  assetMeta.value = nextMeta;
  saveLocalState();
}

function captureVideoThumbnail(asset) {
  if (!asset || thumbnails.value[asset.id] || !String(asset.mime_type || "").startsWith("video/")) return;
  const video = document.createElement("video");
  video.crossOrigin = "anonymous";
  video.muted = true;
  video.playsInline = true;
  video.preload = "metadata";
  video.src = streamUrl(asset.id);

  const cleanup = () => {
    video.removeAttribute("src");
    video.load();
  };

  video.addEventListener("loadeddata", () => {
    try {
      video.currentTime = Math.min(0.2, Number(video.duration || 0) || 0);
    } catch {
      drawVideoThumbnail(asset.id, video, cleanup);
    }
  }, { once: true });

  video.addEventListener("seeked", () => drawVideoThumbnail(asset.id, video, cleanup), { once: true });
  video.addEventListener("error", cleanup, { once: true });
}

function drawVideoThumbnail(assetId, video, cleanup) {
  try {
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || 320;
    canvas.height = video.videoHeight || 180;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    thumbnails.value = { ...thumbnails.value, [assetId]: canvas.toDataURL("image/jpeg", 0.72) };
  } catch {
  } finally {
    cleanup();
  }
}

async function loadAssets() {
  loading.value = true;
  status.value = "";
  try {
    const data = await listAssets(300, 0);
    assets.value = data.items || [];
    ensureAssetMapping();
    for (const item of assets.value) {
      captureVideoThumbnail(item);
    }
  } catch (err) {
    const message = err.message || "";
    if (message.includes("未登录") || message.includes("401") || message.includes("unauthorized")) {
      currentUser.value = null;
      clearAuthToken();
      return;
    }
    status.value = err.message || "加载失败";
  } finally {
    loading.value = false;
  }
}

const selectedProject = computed(() => projects.value.find((p) => p.id === selectedProjectId.value) || null);
const projectAssets = computed(() => assets.value.filter((a) => assetMeta.value[a.id]?.projectId === selectedProjectId.value));
const selectedAsset = computed(() => shareMode.value ? sharedAsset.value : (assets.value.find((a) => a.id === selectedAssetId.value) || null));

const folderFiles = computed(() =>
  projectAssets.value.sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())
);

const entries = computed(() => [
  ...folderFiles.value.map((a) => ({
    key: `a:${a.id}`,
    type: "asset",
    id: a.id,
    name: assetAlias.value[a.id] || a.original_name,
    time: a.created_at,
    raw: a
  }))
]);

const selectedAnnotations = computed(() => {
  if (shareMode.value) {
    return [...sharedAnnotations.value].map(normalizeAnnotation).sort((a, b) => a.timeSec - b.timeSec);
  }
  const list = selectedAssetId.value ? (annotationMap.value[selectedAssetId.value] || []) : [];
  return [...list].map(normalizeAnnotation).sort((a, b) => a.timeSec - b.timeSec);
});

const selectedAssetsForBatch = computed(() =>
  selectedKeys.value
    .filter((k) => k.startsWith("a:"))
    .map((k) => assets.value.find((a) => a.id === k.slice(2)))
    .filter(Boolean)
);

const shareItems = computed(() =>
  Object.entries(savedShares.value)
    .map(([assetId, item]) => ({
      assetId,
      ...item,
      campusLink: item.campusLink || item.link,
      publicLink: item.smartLink || item.publicLink || item.link,
      smartLink: item.smartLink || item.publicLink || item.link
    }))
    .sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime())
);

function formatDate(v) {
  if (!v) return "-";
  return new Date(v).toLocaleString("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
}

function formatTimeLabel(totalSec) {
  const sec = Math.max(0, Math.floor(totalSec || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  if (h > 0) return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function fakeDuration(item) {
  const sec = Math.max(20, Math.floor(Number(item?.size_bytes || 0) / 1300000));
  return formatTimeLabel(sec);
}

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let n = bytes;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  return `${n.toFixed(1)} ${units[i]}`;
}

function selectProject(id) {
  selectedProjectId.value = id;
  view.value = "home";
  selectedKeys.value = [];
  saveLocalState();
}

function createProject() {
  const name = (window.prompt("新项目名称") || "").trim();
  if (!name) return;
  const project = { id: uid(), name, createdAt: now(), folders: [] };
  projects.value.unshift(project);
  selectedProjectId.value = project.id;
  saveLocalState();
}

function openAsset(id) {
  shareMode.value = false;
  shareToken.value = "";
  sharedAsset.value = null;
  sharedAnnotations.value = [];
  selectedAssetId.value = id;
  view.value = "detail";
  selectedKeys.value = [];
  pausedAtSec.value = null;
  pauseDetected.value = false;
}

function onEntryClick(e, entry) {
  if (entry.type === "asset") openAsset(entry.id);
}

function showMenu(e, type = "blank", key = "") {
  e.preventDefault();
  menu.value = { visible: true, x: e.clientX, y: e.clientY, type, key };
}

function closeMenu() {
  menu.value.visible = false;
}

function renameAsset(id) {
  const asset = assets.value.find((a) => a.id === id);
  if (!asset) return;
  const current = assetAlias.value[id] || asset.original_name;
  const name = (window.prompt("重命名文件", current) || "").trim();
  if (!name) return;
  assetAlias.value = { ...assetAlias.value, [id]: name };
  saveLocalState();
}

async function removeAsset(id) {
  const asset = assets.value.find((a) => a.id === id);
  if (!asset) return;
  const name = assetAlias.value[id] || asset.original_name;
  if (!window.confirm(`确定删除「${name}」吗？`)) return;
  status.value = "";
  try {
    await deleteAsset(id);
    assets.value = assets.value.filter((a) => a.id !== id);
    const nextMeta = { ...assetMeta.value };
    const nextAlias = { ...assetAlias.value };
    const nextNotes = { ...annotationMap.value };
    delete nextMeta[id];
    delete nextAlias[id];
    delete nextNotes[id];
    assetMeta.value = nextMeta;
    assetAlias.value = nextAlias;
    annotationMap.value = nextNotes;
    saveLocalState();
    status.value = "删除成功";
  } catch (err) {
    status.value = err.message || "删除失败";
  }
}

async function batchShare() {
  if (!selectedAssetsForBatch.value.length) return;
  const links = [];
  for (const a of selectedAssetsForBatch.value) {
    const data = await createShare(a.id, expiryHours.value, annotationMap.value[a.id] || []);
    const link = toReviewShareLink(data.share_link, "campus");
    shareLinks.value = { ...shareLinks.value, [a.id]: link };
    links.push(`${a.original_name}: ${link}`);
  }
  await navigator.clipboard.writeText(links.join("\n"));
  status.value = "分享链接已复制";
}

function shareBase(channel = "campus") {
  return channel === "public" ? publicShareBase() : window.location.origin;
}

function smartShareBase() {
  const origin = window.location.origin;
  return origin === campusShareBase() ? origin : campusShareBase();
}

function makeShareLinks(rawLink) {
  return {
    campusLink: toReviewShareLink(rawLink, "campus"),
    publicLink: toReviewShareLink(rawLink, "public"),
    smartLink: toReviewShareLink(rawLink, "smart")
  };
}

async function shareAsset(id, channel = "smart") {
  const asset = assets.value.find((a) => a.id === id);
  if (!asset) return;
  const data = await createShare(id, expiryHours.value, annotationMap.value[id] || []);
  const links = makeShareLinks(data.share_link);
  const link = channel === "campus" ? links.campusLink : links.smartLink;
  shareLinks.value = { ...shareLinks.value, [id]: link };
  savedShares.value = {
    ...savedShares.value,
    [id]: {
      name: assetAlias.value[id] || asset.original_name,
      link,
      campusLink: links.campusLink,
      publicLink: links.smartLink,
      smartLink: links.smartLink,
      createdAt: now()
    }
  };
  saveLocalState();
  await navigator.clipboard.writeText(link);
  status.value = "分享链接已复制";
}

async function copyShareLink(link) {
  await navigator.clipboard.writeText(link);
  status.value = "分享链接已复制";
}

function toReviewShareLink(rawLink, channel = "campus") {
  const token = String(rawLink || "").split("/s/").pop();
  if (!token || token === rawLink) return rawLink;
  if (channel === "smart") {
    return `${publicShareBase()}${window.location.pathname}?share=${encodeURIComponent(token)}&campus=${encodeURIComponent(smartShareBase())}`;
  }
  return `${shareBase(channel)}${window.location.pathname}?share=${encodeURIComponent(token)}`;
}

async function onCreateShareInDetail(channel = "smart") {
  if (!selectedAsset.value) return;
  if (shareMode.value) {
    const link = channel === "smart"
      ? `${publicShareBase()}${window.location.pathname}?share=${encodeURIComponent(shareToken.value)}&campus=${encodeURIComponent(smartShareBase())}`
      : `${shareBase(channel)}${window.location.pathname}?share=${encodeURIComponent(shareToken.value)}`;
    await navigator.clipboard.writeText(link);
    status.value = "批注页面分享链接已复制";
    return;
  }
  const data = await createShare(selectedAsset.value.id, expiryHours.value, selectedAnnotations.value);
  const links = makeShareLinks(data.share_link);
  const link = channel === "campus" ? links.campusLink : links.smartLink;
  shareLinks.value = { ...shareLinks.value, [selectedAsset.value.id]: link };
  savedShares.value = {
    ...savedShares.value,
    [selectedAsset.value.id]: {
      name: assetAlias.value[selectedAsset.value.id] || selectedAsset.value.original_name,
      link,
      campusLink: links.campusLink,
      publicLink: links.smartLink,
      smartLink: links.smartLink,
      createdAt: now()
    }
  };
  saveLocalState();
  await navigator.clipboard.writeText(link);
  status.value = "批注页面分享链接已复制";
}

async function uploadOne(file) {
  if (!file) return;
  uploading.value = true;
  status.value = "";
  const startedAt = Date.now();
  uploadProgress.value = { visible: true, name: file.name, percent: 0, speed: "0 KB/s" };
  try {
    await uploadAsset(file, ({ loaded, percent }) => {
      const elapsed = Math.max(0.2, (Date.now() - startedAt) / 1000);
      const bytesPerSecond = loaded / elapsed;
      uploadProgress.value = {
        visible: true,
        name: file.name,
        percent,
        speed: `${formatBytes(bytesPerSecond)}/s`
      };
    });
    await loadAssets();
    uploadProgress.value = { visible: true, name: file.name, percent: 100, speed: uploadProgress.value.speed };
    status.value = "上传成功";
    window.setTimeout(() => {
      uploadProgress.value = { visible: false, name: "", percent: 0, speed: "0 KB/s" };
    }, 1200);
  } catch (err) {
    status.value = err.message || "上传失败";
    uploadProgress.value = { visible: false, name: "", percent: 0, speed: "0 KB/s" };
  } finally {
    uploading.value = false;
  }
}

async function onPickFile(e) {
  await uploadOne(e.target.files?.[0]);
  if (fileInput.value) fileInput.value.value = "";
}

function triggerUpload() {
  fileInput.value?.click();
}

async function onDropUpload(e) {
  e.preventDefault();
  dragging.value = false;
  await uploadOne(e.dataTransfer?.files?.[0]);
}

function onDragOver(e) {
  e.preventDefault();
  dragging.value = true;
}

function onDragLeave(e) {
  if (e.currentTarget === e.target) dragging.value = false;
}

function currentNoteTimeSec() {
  if (pausedAtSec.value !== null) return Number(pausedAtSec.value);
  if (videoRef.value) return Number(videoRef.value.currentTime || 0);
  return 0;
}

async function addAnnotation() {
  if (!selectedAsset.value || !videoRef.value) return;
  const text = noteText.value.trim();
  if (!text) return;
  const note = {
    id: uid(),
    timeSec: currentNoteTimeSec(),
    text,
    author: currentUser.value?.username || "当前用户",
    createdAt: now(),
    replies: []
  };
  if (shareMode.value) {
    sharedAnnotations.value = [...sharedAnnotations.value.map(normalizeAnnotation), note];
  } else {
    const id = selectedAsset.value.id;
    const list = annotationMap.value[id] ? [...annotationMap.value[id]] : [];
    list.push(note);
    annotationMap.value = { ...annotationMap.value, [id]: list };
  }
  noteText.value = "";
  pauseDetected.value = false;
  await persistAnnotations();
}

async function addReply(noteId) {
  if (!selectedAsset.value) return;
  const text = (replyDrafts.value[noteId] || "").trim();
  if (!text) return;
  const source = shareMode.value ? sharedAnnotations.value : (annotationMap.value[selectedAsset.value.id] || []);
  const list = source.map(normalizeAnnotation).map((note) => {
    if (note.id !== noteId) return note;
    return {
      ...note,
      replies: [
        ...note.replies,
        { id: uid(), text, author: currentUser.value?.username || "当前用户", createdAt: now() }
      ]
    };
  });
  if (shareMode.value) {
    sharedAnnotations.value = list;
  } else {
    annotationMap.value = { ...annotationMap.value, [selectedAsset.value.id]: list };
  }
  replyDrafts.value = { ...replyDrafts.value, [noteId]: "" };
  await persistAnnotations();
}

async function removeAnnotation(noteId) {
  if (!selectedAsset.value) return;
  if (shareMode.value) {
    sharedAnnotations.value = sharedAnnotations.value.filter((n) => n.id !== noteId);
  } else {
    const id = selectedAsset.value.id;
    annotationMap.value = {
      ...annotationMap.value,
      [id]: (annotationMap.value[id] || []).filter((n) => n.id !== noteId)
    };
  }
  await persistAnnotations();
}

function onVideoPause() {
  if (!videoRef.value) return;
  pausedAtSec.value = Number(videoRef.value.currentTime || 0);
  pauseDetected.value = true;
}

function onVideoPlay() {
  pauseDetected.value = false;
}

function seekTo(timeSec) {
  if (!videoRef.value) return;
  videoRef.value.currentTime = Number(timeSec || 0);
  videoRef.value.play();
}

async function handleContextAction(action) {
  const { type, key } = menu.value;
  closeMenu();
  if (action === "upload") {
    triggerUpload();
    return;
  }
  if (type === "folder") {
    return;
  }
  if (type === "asset") {
    const id = key.slice(2);
    if (action === "open") openAsset(id);
    if (action === "share") await shareAsset(id, "smart");
    if (action === "share-campus") await shareAsset(id, "campus");
    if (action === "share-public") await shareAsset(id, "public");
    if (action === "rename") renameAsset(id);
    if (action === "delete") await removeAsset(id);
  }
}

onMounted(async () => {
  await bootstrapAuth();
  window.addEventListener("click", closeMenu);
});

onBeforeUnmount(() => {
  window.removeEventListener("click", closeMenu);
});
</script>

<template>
  <div v-if="!authReady" class="auth-loading">正在验证登录状态...</div>

  <div v-else-if="!currentUser" class="auth-shell">
    <section class="auth-card">
      <div class="auth-brand">
        <span class="mark">QM</span>
        <div>
          <h1>青梅审片台</h1>
          <p>登录后进入你的独立项目空间</p>
        </div>
      </div>

      <div class="auth-tabs">
        <button class="auth-tab" :class="{ active: authMode === 'login' }" @click="switchAuthMode('login')">登录</button>
        <button class="auth-tab" :class="{ active: authMode === 'register' }" @click="switchAuthMode('register')">注册</button>
      </div>

      <form class="auth-form" @submit.prevent="submitAuth">
        <label>
          用户名
          <input v-model.trim="authForm.username" class="input" type="text" placeholder="3-32 位字母、数字或 ._-" autocomplete="username" required />
        </label>
        <label>
          密码
          <input v-model="authForm.password" class="input" type="password" placeholder="6-64 位密码" autocomplete="current-password" required />
        </label>
        <p v-if="authError" class="auth-error">{{ authError }}</p>
        <button class="btn primary auth-submit" :disabled="authLoading">
          {{ authLoading ? "提交中..." : (authMode === "login" ? "登录" : "注册并登录") }}
        </button>
      </form>
    </section>
  </div>

  <div v-else class="app">
    <aside class="sidebar">
      <div class="brand">
        <span class="mark small">QM</span>
        <div>
          <strong>项目</strong>
          <span>{{ currentUser.username }}</span>
        </div>
      </div>

      <button class="new-project" @click="createProject">新建项目</button>

      <div class="project-list">
        <button
          v-for="p in projects"
          :key="p.id"
          class="project-item"
          :class="{ active: p.id === selectedProjectId }"
          @click="selectProject(p.id)"
        >
          <span>{{ p.name }}</span>
          <small>{{ assets.filter((a) => assetMeta[a.id]?.projectId === p.id).length }}</small>
        </button>
      </div>

      <button class="logout" @click="onLogout">退出登录</button>
    </aside>

    <main class="main">
      <header class="topbar">
        <div>
          <p class="crumb">{{ selectedProject?.name || "-" }}</p>
          <h1>{{ view === "home" ? "素材库" : "批注页面" }}</h1>
        </div>
        <div class="top-actions">
          <input ref="fileInput" type="file" class="hidden" @change="onPickFile" />
          <div v-if="uploadProgress.visible" class="upload-toast">
            <strong>{{ uploadProgress.percent }}%</strong>
            <span>{{ uploadProgress.speed }}</span>
            <small>{{ uploadProgress.name }}</small>
          </div>
          <button class="btn ghost" @click="loadAssets">刷新</button>
          <button class="btn primary" :disabled="uploading" @click="triggerUpload">{{ uploading ? "上传中..." : "上传" }}</button>
        </div>
      </header>

      <p v-if="status" class="status">{{ status }}</p>

      <template v-if="view === 'home'">
        <section class="library">
          <nav class="library-tabs">
            <button :class="{ active: activeTab === 'files' }" @click="activeTab = 'files'">文件</button>
            <button :class="{ active: activeTab === 'shares' }" @click="activeTab = 'shares'">分享</button>
          </nav>

          <div
            class="canvas"
            :class="{ dragging }"
            @dragover="onDragOver"
            @dragleave="onDragLeave"
            @drop="onDropUpload"
            @contextmenu="showMenu($event, 'blank', '')"
          >
            <div v-if="dragging" class="drop-hint">松开即可上传到当前项目</div>

            <template v-if="activeTab === 'files'">
              <div v-if="loading" class="empty">加载中...</div>
              <div v-else-if="entries.length === 0" class="empty">
                当前目录为空，可以拖拽文件到这里或右键选择上传
              </div>
              <div v-else class="grid">
                <div
                  v-for="entry in entries"
                  :key="entry.key"
                  class="asset-card"
                  :class="{ folder: entry.type === 'folder' }"
                  @click.stop="onEntryClick($event, entry)"
                  @contextmenu.stop="showMenu($event, entry.type, entry.key)"
                >
                  <div class="thumb" :style="entry.type === 'asset' && thumbnails[entry.id] ? { backgroundImage: `url(${thumbnails[entry.id]})` } : null">
                    <span class="duration">▣ {{ fakeDuration(entry.raw) }}</span>
                  </div>
                  <div class="card-body">
                    <div class="file-name">{{ entry.name }}</div>
                    <div class="file-date">{{ entry.type === "asset" ? formatDate(entry.time) : "文件夹" }}</div>
                    <button class="more" @click.stop="showMenu($event, entry.type, entry.key)">⋮</button>
                  </div>
                </div>
              </div>
            </template>

            <template v-else-if="activeTab === 'shares'">
              <div v-if="shareItems.length === 0" class="empty">暂无分享链接，在文件三点菜单里选择分享后会显示在这里</div>
              <div v-else class="share-list">
                <article v-for="item in shareItems" :key="item.assetId" class="share-card">
                  <div>
                    <strong>{{ item.name }}</strong>
                    <p>{{ formatDate(item.createdAt) }}</p>
                    <a :href="item.campusLink" target="_blank" rel="noreferrer">Campus: {{ item.campusLink }}</a>
                    <a :href="item.smartLink" target="_blank" rel="noreferrer">Smart: {{ item.smartLink }}</a>
                  </div>
                  <div class="share-actions">
                    <button class="btn" @click="copyShareLink(item.campusLink)">复制校园网</button>
                    <button class="btn primary" @click="copyShareLink(item.smartLink)">复制智能链接</button>
                  </div>
                </article>
              </div>
            </template>
          </div>
        </section>
      </template>

      <template v-else>
        <section class="detail">
          <button class="back" @click="view = 'home'">返回素材库</button>
          <div v-if="selectedAsset">
            <div class="detail-title">
              <div>
                <h2>{{ assetAlias[selectedAsset.id] || selectedAsset.original_name }}</h2>
                <p>{{ selectedAsset.mime_type }} · {{ formatBytes(selectedAsset.size_bytes) }}</p>
              </div>
              <button class="btn primary" @click="onCreateShareInDetail">复制批注页面链接</button>
            </div>

            <div class="player">
              <video
                ref="videoRef"
                controls
                class="video"
                :src="shareMode ? shareStreamUrl(shareToken) : streamUrl(selectedAsset.id)"
                @pause="onVideoPause"
                @play="onVideoPlay"
              ></video>
            </div>

            <section class="note-block">
              <div class="note-head">
                <h3>时间轴批注</h3>
                <span class="time-chip" :class="{ active: pauseDetected }">
                  {{ pauseDetected ? "暂停点" : "当前点" }} {{ formatTimeLabel(currentNoteTimeSec()) }}
                </span>
              </div>
              <textarea v-model="noteText" class="textarea" rows="3" placeholder="输入批注，发送后自动绑定到时间点"></textarea>
              <div class="note-actions">
                <button class="btn primary" @click="addAnnotation">发送</button>
              </div>

              <div v-if="selectedAnnotations.length === 0" class="small-empty">暂无批注</div>
              <ul v-else class="note-list">
                <li v-for="n in selectedAnnotations" :key="n.id" class="note-item">
                  <button class="time-tag" @click="seekTo(n.timeSec)">{{ formatTimeLabel(n.timeSec) }}</button>
                  <div>
                    <div class="note-meta">{{ n.author }} · {{ formatDate(n.createdAt) }}</div>
                    <div class="note-text">{{ n.text }}</div>
                    <div v-if="n.replies.length" class="reply-list">
                      <div v-for="r in n.replies" :key="r.id" class="reply">
                        <strong>{{ r.author }}</strong>
                        <span>{{ r.text }}</span>
                      </div>
                    </div>
                    <div class="reply-box">
                      <input v-model="replyDrafts[n.id]" class="reply-input" placeholder="回复这条批注" @keyup.enter="addReply(n.id)" />
                      <button @click="addReply(n.id)">回复</button>
                    </div>
                  </div>
                  <button class="del" @click="removeAnnotation(n.id)">删除</button>
                </li>
              </ul>
            </section>
          </div>
          <div v-else class="small-empty">未找到素材</div>
        </section>
      </template>

      <div v-if="menu.visible" class="ctx-menu" :style="{ left: `${menu.x}px`, top: `${menu.y}px` }">
        <button v-if="menu.type === 'blank'" @click="handleContextAction('upload')">上传文件</button>

        <template v-if="menu.type === 'asset'">
          <button @click="handleContextAction('open')">打开批注页</button>
          <button @click="handleContextAction('share')">分享智能链接</button>
          <button @click="handleContextAction('rename')">重命名</button>
          <button class="danger" @click="handleContextAction('delete')">删除</button>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.auth-loading,
.auth-shell {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: #f5f7fb;
  color: #172033;
}

.auth-card {
  width: min(440px, 100%);
  border: 1px solid #e4e9f2;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.1);
  padding: 26px;
}

.auth-brand,
.brand,
.topbar,
.top-actions,
.detail-title,
.note-head,
.note-actions,
.batch-bar {
  display: flex;
  align-items: center;
}

.auth-brand,
.brand {
  gap: 12px;
}

.mark {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: #2563eb;
  color: #fff;
  font-weight: 800;
}

.mark.small {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  font-size: 13px;
}

.auth-brand h1,
.auth-brand p,
.crumb,
h1,
h2,
h3 {
  margin: 0;
}

.auth-brand p,
.brand span,
.crumb,
.file-date,
.note-meta,
.detail-title p {
  color: #64748b;
}

.auth-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  border-radius: 12px;
  background: #f1f5f9;
  padding: 5px;
  margin: 22px 0 18px;
}

.auth-tab {
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #64748b;
  padding: 10px;
  cursor: pointer;
  font-weight: 700;
}

.auth-tab.active {
  background: #fff;
  color: #1e40af;
  box-shadow: 0 4px 14px rgba(30, 64, 175, 0.1);
}

.auth-form {
  display: grid;
  gap: 14px;
}

label {
  display: grid;
  gap: 7px;
  color: #334155;
  font-size: 13px;
  font-weight: 700;
}

.input,
.textarea,
.reply-input {
  border: 1px solid #dbe3ee;
  border-radius: 10px;
  background: #fff;
  color: #172033;
  padding: 11px 12px;
  outline: none;
}

.auth-error,
.status {
  margin: 0;
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13px;
}

.auth-error {
  border: 1px solid #fecdd3;
  background: #fff1f2;
  color: #be123c;
}

.app {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 270px 1fr;
  background: #f5f7fb;
  color: #111827;
}

.sidebar {
  border-right: 1px solid #e2e8f0;
  background: #fff;
  padding: 18px 14px;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.brand {
  margin-bottom: 18px;
}

.brand span {
  display: block;
  font-size: 12px;
  margin-top: 2px;
}

.new-project,
.logout {
  border: 1px solid #d8e1ed;
  border-radius: 10px;
  background: #f8fafc;
  color: #2563eb;
  padding: 10px 12px;
  cursor: pointer;
  font-weight: 800;
}

.project-list {
  display: grid;
  gap: 6px;
  margin-top: 12px;
}

.project-item {
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #334155;
  cursor: pointer;
}

.project-item {
  display: flex;
  justify-content: space-between;
  padding: 10px 11px;
  text-align: left;
}

.project-item.active {
  background: #eef5ff;
  color: #1d4ed8;
}

.logout {
  margin-top: auto;
  color: #64748b;
}

.main {
  padding: 18px 20px;
  min-width: 0;
  position: relative;
}

.topbar {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

h1 {
  font-size: 26px;
  margin-top: 3px;
}

.top-actions {
  gap: 8px;
  flex-wrap: wrap;
}

.upload-toast {
  min-width: 190px;
  border: 1px solid #bfdbfe;
  border-radius: 12px;
  background: #eff6ff;
  color: #1d4ed8;
  padding: 7px 10px;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 8px;
  align-items: center;
}

.upload-toast strong {
  font-size: 16px;
}

.upload-toast span {
  text-align: right;
  font-size: 12px;
  font-weight: 800;
}

.upload-toast small {
  grid-column: 1 / -1;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hidden {
  display: none;
}

.btn {
  border: 1px solid #d6e0ec;
  border-radius: 10px;
  padding: 9px 13px;
  cursor: pointer;
  color: #334155;
  background: #fff;
  font-weight: 700;
}

.btn.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.btn.light,
.btn.ghost {
  background: #f8fafc;
}

.status {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1d4ed8;
  margin-bottom: 12px;
}

.library {
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  background: #fff;
  overflow: hidden;
  min-height: calc(100vh - 104px);
}

.library-tabs {
  height: 56px;
  display: flex;
  gap: 28px;
  align-items: center;
  padding: 0 18px;
  border-bottom: 1px solid #edf1f7;
}

.library-tabs button {
  height: 56px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-weight: 800;
}

.library-tabs button.active {
  color: #1d4ed8;
  border-bottom-color: #2563eb;
}

.canvas {
  position: relative;
  min-height: calc(100vh - 162px);
  padding: 16px;
  background: #fbfcfe;
}

.canvas.dragging {
  background: #f0f6ff;
  box-shadow: inset 0 0 0 2px #60a5fa;
}

.drop-hint {
  position: absolute;
  inset: 16px;
  z-index: 5;
  display: grid;
  place-items: center;
  border: 2px dashed #60a5fa;
  border-radius: 14px;
  background: rgba(239, 246, 255, 0.86);
  color: #1d4ed8;
  font-weight: 900;
  pointer-events: none;
}

.batch-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.batch-bar {
  align-items: center;
  background: #fff;
  border: 1px solid #dbe6f3;
  border-radius: 12px;
  padding: 10px 12px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 232px));
  gap: 18px;
  align-content: start;
}

.asset-card {
  width: 232px;
  border: 1px solid #e1e7f0;
  border-radius: 8px;
  background: #fff;
  overflow: hidden;
  padding: 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.06);
}

.asset-card.selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.14);
}

.thumb {
  height: 128px;
  display: grid;
  place-items: end start;
  padding: 10px;
  background: linear-gradient(135deg, #e8f0ff, #eefdf4);
  background-size: cover;
  background-position: center;
  position: relative;
}

.duration {
  color: #fff;
  background: rgba(15, 23, 42, 0.82);
  border-radius: 4px;
  padding: 3px 7px;
  font-size: 12px;
}

.card-body {
  position: relative;
  padding: 12px 36px 12px 12px;
}

.file-name {
  color: #111827;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-date {
  margin-top: 5px;
  font-size: 12px;
}

.more {
  position: absolute;
  right: 10px;
  top: 21px;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 20px;
  line-height: 1;
}

.empty,
.small-empty {
  min-height: 260px;
  display: grid;
  place-items: center;
  border: 1px dashed #cad5e4;
  border-radius: 14px;
  background: #fff;
  color: #64748b;
}

.share-list {
  display: grid;
  gap: 12px;
}

.share-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  padding: 14px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  align-items: center;
}

.share-card p {
  margin: 4px 0;
  color: #64748b;
  font-size: 12px;
}

.share-card a {
  display: block;
  color: #2563eb;
  word-break: break-all;
  margin-top: 4px;
}

.share-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.detail {
  border: 1px solid #e2e8f0;
  border-radius: 16px;
  background: #fff;
  padding: 16px;
}

.back {
  border: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  margin-bottom: 14px;
  padding: 0;
  font-weight: 800;
}

.detail-title {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.detail-title p {
  margin: 5px 0 0;
}

.player {
  border: 1px solid #dce5f0;
  border-radius: 14px;
  overflow: hidden;
  background: #0f172a;
}

.video {
  width: 100%;
  display: block;
  aspect-ratio: 16/9;
  max-height: 70vh;
  object-fit: contain;
}

.note-block {
  margin-top: 16px;
}

.note-head {
  justify-content: space-between;
  margin-bottom: 10px;
}

.time-chip {
  border: 1px solid #d7e3f2;
  background: #f8fafc;
  color: #475569;
  border-radius: 999px;
  font-size: 12px;
  padding: 6px 10px;
}

.time-chip.active {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.textarea {
  width: 100%;
  min-height: 86px;
  resize: vertical;
  line-height: 1.5;
}

.note-actions {
  justify-content: flex-end;
  margin: 10px 0;
}

.note-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 360px;
  overflow: auto;
}

.note-item {
  border-bottom: 1px solid #e5eaf2;
  padding: 12px 2px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: start;
}

.time-tag,
.del {
  border: 0;
  background: transparent;
  cursor: pointer;
  padding: 0;
}

.time-tag {
  color: #2563eb;
  font-weight: 800;
}

.note-text {
  color: #334155;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.del {
  color: #94a3b8;
}

.reply-list {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}

.reply {
  border-left: 3px solid #dbeafe;
  background: #f8fafc;
  border-radius: 8px;
  padding: 8px 10px;
  color: #334155;
  font-size: 13px;
}

.reply strong {
  margin-right: 8px;
  color: #1d4ed8;
}

.reply-box {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.reply-input {
  flex: 1;
  padding: 8px 10px;
}

.reply-box button {
  border: 1px solid #d6e0ec;
  border-radius: 9px;
  background: #fff;
  color: #2563eb;
  cursor: pointer;
  font-weight: 800;
  padding: 0 12px;
}

.ctx-menu {
  position: fixed;
  z-index: 1000;
  min-width: 154px;
  background: #fff;
  border: 1px solid #dbe4ef;
  border-radius: 12px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.16);
  padding: 6px;
}

.ctx-menu button {
  width: 100%;
  border: 0;
  text-align: left;
  background: transparent;
  color: #334155;
  border-radius: 8px;
  padding: 9px;
  cursor: pointer;
}

.ctx-menu button:hover {
  background: #f1f5f9;
}

.ctx-menu button.danger {
  color: #dc2626;
}

.ctx-menu button.danger:hover {
  background: #fef2f2;
}

@media (max-width: 980px) {
  .app {
    grid-template-columns: 1fr;
  }

  .sidebar {
    min-height: auto;
    border-right: 0;
    border-bottom: 1px solid #e2e8f0;
  }

  .topbar,
  .detail-title {
    flex-direction: column;
    align-items: flex-start;
  }

  .grid {
    grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  }

  .asset-card {
    width: 100%;
  }
}
</style>
