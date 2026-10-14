# open_health's `oura` binary (ring sync + web dashboard), built at a pinned
# commit. Bump OPEN_HEALTH_REV by hand after reviewing upstream changes; it
# pins open_oura itself through its Cargo.toml.
#
# The `runtime` stage serves the dashboard (no Bluetooth, no auth key). The
# host's oura-sync unit runs the same binary, copied out of the `build` stage.

FROM rust:1.90-bookworm AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends libdbus-1-dev pkg-config \
 && rm -rf /var/lib/apt/lists/*
ARG OPEN_HEALTH_REV=08e1b868e1c5ad9c5c63c2adedaee86c3e1c1bfa
RUN git clone https://github.com/Th0rgal/open_health /src \
 && git -C /src checkout --detach "$OPEN_HEALTH_REV"
# Our changes to upstream's web UI (patches/README.md). `git apply` refuses a
# patch that no longer matches, so a pin bump fails the build rather than
# shipping a half-patched page.
COPY patches/ /patches/
RUN git -C /src apply --verbose /patches/*.patch
WORKDIR /src
RUN cargo build --release --locked -p oura-cli

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends libdbus-1-3 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/release/oura /usr/local/bin/oura
# The dashboard locates its web assets and model runners by finding
# tools/run_activity_model.py above the working directory. The runners need
# Python, torch and Oura's models, none of which are here, so they return
# nothing and the dashboard shows the rest.
COPY --from=build /src/dashboard/web /app/dashboard/web
COPY --from=build /src/tools /app/tools
WORKDIR /app
USER 1000:1000
ENTRYPOINT ["oura"]
