# syntax=docker/dockerfile:1.7
# SPDX-FileCopyrightText: Metacog Labs
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY engine/pom.xml engine/pom.xml
COPY database/pom.xml database/pom.xml
COPY server/pom.xml server/pom.xml
COPY engine/src engine/src
COPY database/src database/src
COPY server/src server/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -DskipTests install \
 && ./mvnw -q -B -Pnative -DskipTests package -pl server \
 && cp server/target/hstore /hstore

FROM debian:bookworm-slim
ARG UID=999
ARG GID=999
RUN groupadd --system --gid ${GID} hstore \
 && useradd --system --uid ${UID} --gid hstore --home-dir /var/lib/hstore --shell /usr/sbin/nologin hstore \
 && mkdir -p /var/lib/hstore/data /docker-entrypoint-initdb.d \
 && chown -R hstore:hstore /var/lib/hstore \
 && chmod 700 /var/lib/hstore/data
COPY --from=build /hstore /usr/local/bin/hstore
COPY docker/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENV HSTORE_DATA=/var/lib/hstore/data \
    HSTORE_LISTEN_ADDRESS=0.0.0.0 \
    HSTORE_PORT=7432 \
    HSTORE_STUDIO_PORT=7480
VOLUME /var/lib/hstore/data
EXPOSE 7432 7480
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD hstore ping "127.0.0.1:${HSTORE_PORT}" > /dev/null || exit 1
USER hstore
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["serve"]
