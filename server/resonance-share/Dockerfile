FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -o /resonance-share .

FROM gcr.io/distroless/static-debian12
COPY --from=build /resonance-share /resonance-share
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/resonance-share"]
