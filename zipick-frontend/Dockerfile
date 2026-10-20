# 프론트 이미지: 정적 파일을 빌드해 Caddy 로 서빙한다. /api 는 Caddy 가 백엔드로 넘긴다.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 카카오 JavaScript 키는 브라우저에 그대로 노출되는 공개 키다(도메인 등록으로 보호). 빌드 인자로 받는다.
ARG VITE_KAKAO_MAP_KEY=""
ENV VITE_KAKAO_MAP_KEY=$VITE_KAKAO_MAP_KEY
RUN npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
