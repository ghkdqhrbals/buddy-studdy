FROM grafana/k6:0.54.0 AS k6

FROM node:22-alpine

RUN apk add --no-cache docker-cli tini

WORKDIR /app
COPY --from=k6 /usr/bin/k6 /usr/local/bin/k6
COPY package.json ./
COPY src ./src

ENV NODE_ENV=production
ENV TESTZONE_PORT=3020
ENV TESTZONE_DATA_DIR=/data

EXPOSE 3020
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "src/server.mjs"]
