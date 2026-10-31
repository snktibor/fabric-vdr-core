# The reproduce image. With nothing but Docker on the host,
#   docker build -f reproduce.Dockerfile -t fabric-vdr-reproduce .
#   docker run --rm fabric-vdr-reproduce
# runs `just check`: the unit tests, the Lean proofs of argos-formal with its mutation check, a
# print-out of the committed scalability results, and the CSVs behind the paper's figures, which
# `just figures` regenerates from those results. `docker run --rm fabric-vdr-reproduce formal`
# runs one recipe. The E2E suite and the measurement need a live Fabric network, so they run on
# the host (`just e2e`, `just benchmark`) or in CI, not in here.
FROM maven:3.9.16-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends just patch bsdextrautils python3 \
    && rm -rf /var/lib/apt/lists/*

# Lean: elan, then the toolchain that argos-formal's lean-toolchain pins.
ENV ELAN_HOME=/opt/elan PATH=/opt/elan/bin:$PATH ARGOS_FORMAL_DIR=/opt/argos-formal
RUN curl -sSfL "https://github.com/leanprover/elan/releases/download/v4.2.4/elan-$(uname -m)-unknown-linux-gnu.tar.gz" \
    | tar xz -C /tmp && /tmp/elan-init -y --no-modify-path --default-toolchain none && rm /tmp/elan-init

WORKDIR /work
COPY justfile .
RUN just formal-src && cd "$ARGOS_FORMAL_DIR" && lake --version

# The Maven dependencies in their own layer, so a source change does not download them again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY . .

ENTRYPOINT ["just"]
CMD ["check"]
