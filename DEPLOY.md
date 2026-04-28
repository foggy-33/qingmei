# Deploy qingmei with Git and Docker

Domain: `qm.upcshare.cn`

## 1. DNS

Create an `A` record:

```text
qm.upcshare.cn -> your_server_public_ip
```

Open server firewall/security-group ports:

```text
80/tcp
443/tcp
```

Do not expose PostgreSQL or Redis to the public internet.

## 2. Clone

```bash
sudo mkdir -p /opt/qingmei
sudo chown -R "$USER":"$USER" /opt/qingmei
git clone <your-git-repo-url> /opt/qingmei
cd /opt/qingmei
```

## 3. Configure

```bash
cp .env.prod.example .env
nano .env
```

Change `POSTGRES_PASSWORD`.

## 4. Start

```bash
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
```

Caddy will automatically request and renew HTTPS certificates for `qm.upcshare.cn`.

## 5. Update

```bash
cd /opt/qingmei
git pull
docker compose --env-file .env -f docker-compose.prod.yml up -d --build
```

## 6. Logs

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs -f web
```

