FROM node:24-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY *.mjs ./
RUN mkdir -p /app/data && chown -R node:node /app/data
USER node
ENV LOG_DIR=/app/data
ENV NODE_ENV=production
EXPOSE 8080
CMD ["node", "server.mjs"]
