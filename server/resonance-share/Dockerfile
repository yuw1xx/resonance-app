FROM golang:1.24-alpine AS build
# The release workflow passes the version from the tag (server-v2.1.0 -> 2.1.0).
ARG VERSION=2.0.0
WORKDIR /src
COPY go.mod *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.serverVersion=${VERSION}" -o /resonance-share .

FROM gcr.io/distroless/static-debian12
COPY --from=build /resonance-share /resonance-share
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/resonance-share"]
