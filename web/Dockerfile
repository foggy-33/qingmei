## syntax=docker/dockerfile:1.7
FROM node:20-alpine AS builder
WORKDIR /app

ARG VITE_API_BASE=
ARG VITE_CAMPUS_SHARE_BASE=https://qm.upcshare.cn:8088
ARG VITE_PUBLIC_SHARE_BASE=https://qm.upcshare.cn:8443
ENV VITE_API_BASE=$VITE_API_BASE
ENV VITE_CAMPUS_SHARE_BASE=$VITE_CAMPUS_SHARE_BASE
ENV VITE_PUBLIC_SHARE_BASE=$VITE_PUBLIC_SHARE_BASE

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=builder /app/dist /srv
