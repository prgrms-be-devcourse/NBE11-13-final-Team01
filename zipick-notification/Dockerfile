# 알림센터(Go) 이미지. 빌드 단계에서 실행 파일 하나만 만들고, 실행 이미지는 알파인 + 인증서 + 시간대 데이터만.
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd cmd
COPY internal internal
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /notification ./cmd/server

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 1001 zipick
COPY --from=build /notification /notification
COPY templates /templates
USER zipick
ENV TZ=Asia/Seoul \
    NOTIFICATION_TEMPLATES_DIR=/templates
EXPOSE 8081
ENTRYPOINT ["/notification"]
