# 审片平台 (Spring Boot + PostgreSQL + Redis + Vue3)

这是一个可运行的审片平台 MVP，支持：

- 在线预览（流式访问）
- 分享链接（可设置过期时间）
- 下载原文件
- Redis 缓存素材元数据
- Redis 队列驱动审片任务状态（`queued -> processing -> ready`）
- 前端控制台（Vue 3 + Vite + Tailwind CSS）

## 技术栈

- 后端：Spring Boot
- 前端：Vue 3 + Vite + Tailwind CSS
- 数据库：PostgreSQL
- 缓存/队列：Redis
- 存储：本地文件系统（`STORAGE_DIR`）

## 启动方式

### 方式 1：Docker Compose（推荐）

```bash
docker compose up --build
```

服务默认地址：

- Frontend: `http://localhost:5173`
- API: `http://localhost:8080`
- PostgreSQL: `localhost:5432`
- Redis: `localhost:6379`

### 方式 2：本地 Spring Boot

1. 安装 JDK 17+ 与 Maven 3.9+
2. 准备 PostgreSQL 和 Redis
3. 配置环境变量（可参考 `.env.example`）
4. 运行：

```bash
mvn spring-boot:run
```

### 方式 3：本地前端开发

进入 `web/`：

```bash
npm install
npm run dev
```

前端默认地址：`http://localhost:5173`  
可通过环境变量覆盖后端地址：`VITE_API_BASE=http://localhost:8080`

## API 示例

### 1) 上传素材

```bash
curl -X POST "http://localhost:8080/api/v1/assets/upload" \
  -F "file=@./demo.mp4"
```

返回示例：

```json
{
  "asset": {
    "id": "xxx",
    "original_name": "demo.mp4",
    "stored_name": "1711860000000000000.mp4",
    "mime_type": "video/mp4",
    "size_bytes": 12345,
    "created_at": "2026-03-31T06:00:00Z"
  },
  "preview_url": "/api/v1/assets/xxx/stream",
  "download_url": "/api/v1/assets/xxx/download"
}
```

### 2) 列表查询

```bash
curl "http://localhost:8080/api/v1/assets?limit=20&offset=0"
```

### 3) 获取素材详情

```bash
curl "http://localhost:8080/api/v1/assets/{asset_id}"
```

### 4) 在线预览

浏览器直接打开：

`http://localhost:8080/api/v1/assets/{asset_id}/stream`

### 5) 下载

浏览器或命令行：

```bash
curl -L "http://localhost:8080/api/v1/assets/{asset_id}/download" -o out.bin
```

### 6) 生成分享链接

```bash
curl -X POST "http://localhost:8080/api/v1/assets/{asset_id}/share" \
  -H "Content-Type: application/json" \
  -d "{\"expiry_hours\": 24}"
```

返回示例：

```json
{
  "share": {
    "id": "xxx",
    "asset_id": "xxx",
    "token": "abc123...",
    "expires_at": "2026-04-01T06:00:00Z",
    "created_at": "2026-03-31T06:00:00Z"
  },
  "share_link": "http://localhost:8080/s/abc123..."
}
```

### 7) 访问分享链接

浏览器打开：

`http://localhost:8080/s/{token}`

会重定向到对应素材的预览流地址。

## 目录结构

```text
cmd/server/main.go
internal/config
internal/model
internal/repository
internal/server
internal/service
internal/storage
web/
web/src/App.vue
web/src/api.js
```
