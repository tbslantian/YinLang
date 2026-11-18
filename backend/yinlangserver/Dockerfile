# LXServer v2.2.0 uses the upstream Node 22 Alpine build stages.
FROM --platform=$BUILDPLATFORM node:22-alpine AS source
RUN apk add --no-cache unzip
COPY .reference/lxserver.zip /tmp/lxserver.zip
RUN echo '39ac05cfeb006a4cf9bdb8723c1a32886f5b6cd55085e3288c58ad62a1fc79c1  /tmp/lxserver.zip' | sha256sum -c - \
    && unzip -q /tmp/lxserver.zip -d /tmp

FROM --platform=$BUILDPLATFORM node:22-alpine AS builder
WORKDIR /source-code
COPY --from=source /tmp/lxserver-2.2.0/ ./
COPY backend/yinlangserver/brand.mjs /tmp/brand.mjs
COPY backend/yinlangserver/mount.mjs /tmp/mount.mjs
RUN node /tmp/brand.mjs /source-code && node /tmp/mount.mjs /source-code --source
RUN (apk add --no-cache chromaprint || true) \
    && npm ci --ignore-scripts --no-audit --no-fund \
    && npm run build

FROM node:22-alpine AS deps
WORKDIR /deps
COPY --from=source /tmp/lxserver-2.2.0/package.json /tmp/lxserver-2.2.0/package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund

FROM node:22-alpine
WORKDIR /server
LABEL org.opencontainers.image.title="yinlangserver" \
      org.opencontainers.image.version="2.2.0-4"
RUN apk add --no-cache ffmpeg && (apk add --no-cache chromaprint || true)
RUN node -e "require('node:sqlite')"
COPY --from=deps /deps/node_modules ./node_modules
COPY --from=builder /source-code/server ./server
COPY --from=builder /source-code/public ./public
COPY --from=builder /source-code/config.js /source-code/index.js /source-code/package.json /source-code/LICENSE ./

COPY backend/server.mjs ./yinlang/push.mjs
COPY backend/yinlangserver/mount.mjs ./yinlang/mount.mjs
RUN node ./yinlang/mount.mjs && node --check ./server/server/server.js

VOLUME /server/data
EXPOSE 9527
ENV NODE_ENV=production \
    PORT=9527 \
    BIND_IP=0.0.0.0 \
    DATA_PATH=/server/data \
    LOG_PATH=/server/data/logs \
    SERVER_NAME=yinlangserver \
    PLAYER_NAME=yinlangserver \
    YINLANG_LXSERVER=1

CMD ["node", "--import", "./yinlang/push.mjs", "index.js"]
