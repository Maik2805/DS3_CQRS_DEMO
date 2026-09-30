# syntax=docker/dockerfile:1

# --- Stage 1: build with Node LTS ---
FROM node:22-alpine AS build
WORKDIR /build

# Install all deps (including dev) for the TypeScript build.
COPY package.json package-lock.json* ./
RUN npm install

COPY tsconfig.json nest-cli.json ./
COPY src ./src
RUN npm run build

# --- Stage 2: production runtime ---
FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

# Install production dependencies only.
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

COPY --from=build /build/dist ./dist

# Read API (container-internal port; compose maps it to host 8082).
EXPOSE 3000

CMD ["node", "dist/main.js"]
