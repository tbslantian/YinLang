FROM node:22-alpine
WORKDIR /app
COPY --chown=node:node backend/server.mjs backend/index.html shared/catalog.bundle.js shared/catalog-licenses.txt ./
RUN apk add --no-cache ffmpeg && mkdir -p /state /downloads/music /downloads/audiobooks && chown -R node:node /state /downloads
USER node
EXPOSE 8787
CMD ["node", "server.mjs"]
