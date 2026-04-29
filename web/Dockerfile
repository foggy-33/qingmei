## syntax=docker/dockerfile:1.7
FROM node:20-alpine AS builder
WORKDIR /app

ARG VITE_API_BASE=https://qm.upcshare.cn
ENV VITE_API_BASE=$VITE_API_BASE

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=builder /app/dist /srv
