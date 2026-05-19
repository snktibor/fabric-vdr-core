#!/bin/sh
set -e

# Bridge Fablo's CCaaS env vars to fabric-chaincode-shim 2.5.x's expected names,
# and supply the TLS material in BOTH formats the shim needs:
#
#   CORE_TLS_CLIENT_CERT_FILE / *_KEY_FILE  -> raw PEM, used by NettyGrpcServer
#       (the chaincode's server-side TLS handshake with the peer)
#   CORE_TLS_CLIENT_CERT_PATH / *_KEY_PATH  -> base64(PEM), used by
#       ChaincodeBase.createSSLContext for the internal back-channel reached
#       through ChatChaincodeWithPeer.connect -> connectToPeer(observer).
#
# Fablo only sets CORE_CHAINCODE_TLS_*_FILE pointing at raw PEM, so the shim
# crashes with "Illegal base64 character 2d" (the leading '-' of '-----BEGIN')
# the first time a peer opens a stream. We re-encode the PEM into /tmp and
# point *_PATH at the encoded copies.
TLS_SRC_CERT="${CORE_CHAINCODE_TLS_CERT_FILE:-/etc/hyperledger/fabric/client.crt}"
TLS_SRC_KEY="${CORE_CHAINCODE_TLS_KEY_FILE:-/etc/hyperledger/fabric/client.key}"

if [ -f "$TLS_SRC_CERT" ] && [ -f "$TLS_SRC_KEY" ]; then
  # *_FILE: raw PEM as-is (the shim's NettyGrpcServer reads these directly).
  export CORE_TLS_CLIENT_CERT_FILE="$TLS_SRC_CERT"
  export CORE_TLS_CLIENT_KEY_FILE="$TLS_SRC_KEY"

  # *_PATH: base64(PEM) (the shim's createSSLContext base64-decodes file bytes).
  TLS_DIR=/tmp/shim-tls
  mkdir -p "$TLS_DIR"
  base64 -w0 < "$TLS_SRC_CERT" > "$TLS_DIR/client.crt.b64"
  base64 -w0 < "$TLS_SRC_KEY"  > "$TLS_DIR/client.key.b64"
  export CORE_TLS_CLIENT_CERT_PATH="$TLS_DIR/client.crt.b64"
  export CORE_TLS_CLIENT_KEY_PATH="$TLS_DIR/client.key.b64"
fi

# In CCaaS server mode the chaincode listens; the legacy CORE_PEER_ADDRESS that
# Fablo also injects ("127.0.0.1:7051") makes our bootstrap append a
# --peerAddress CLI arg meant for client mode, which can confuse the shim.
if [ -n "$CHAINCODE_SERVER_ADDRESS" ]; then
  unset CORE_PEER_ADDRESS
fi

exec java -jar /root/chaincode-java/chaincode/chaincode.jar "$@"
