#!/bin/sh
# SPDX-FileCopyrightText: 2023 Deutsche Telekom AG
#
# SPDX-License-Identifier: Apache-2.0
#
# Seed two OAuth2 clients into Ory Hydra on first start.
#
#  * gateway-test-cc : client_credentials grant, scopes "read write"
#  * gateway-test-ac : authorization_code + refresh_token, scopes "openid email"
#
# Client secrets are generated at runtime with openssl rand and written to
# a JSON file on a shared volume. The script is idempotent in the sense
# that it always converges on a known-good state: if a client already
# exists (from a previous run), it is deleted and re-created with a fresh
# secret. Hydra stores only hashed secrets, so there is no way to recover
# the previous plaintext — re-seeding therefore means "rotate secrets".
#
# Environment contract:
#   HYDRA_ADMIN_URL   (default http://hydra:4445)
#   HYDRA_PUBLIC_URL  (default http://hydra:4444)
#   SECRETS_DIR       (default /secrets)

set -eu

HYDRA_ADMIN_URL="${HYDRA_ADMIN_URL:-http://hydra:4445}"
HYDRA_PUBLIC_URL="${HYDRA_PUBLIC_URL:-http://hydra:4444}"
SECRETS_DIR="${SECRETS_DIR:-/secrets}"
OUT_FILE="${SECRETS_DIR}/hydra-clients.json"

mkdir -p "${SECRETS_DIR}"

# ---------------------------------------------------------------------------
# 1. Wait for the admin API to be ready (bounded poll, max 60s).
# ---------------------------------------------------------------------------
echo "[seed] waiting for Hydra admin API at ${HYDRA_ADMIN_URL} ..."
i=0
until curl -fsS "${HYDRA_ADMIN_URL}/health/ready" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "${i}" -ge 30 ]; then
        echo "[seed] ERROR: Hydra admin API did not become ready in 60s" >&2
        exit 1
    fi
    sleep 2
done
echo "[seed] Hydra admin API is ready."

# ---------------------------------------------------------------------------
# 2. Install openssl if not present (curlimages/curl has busybox; openssl
#    is shipped separately on Alpine). We use apk add for the test image.
# ---------------------------------------------------------------------------
if ! command -v openssl >/dev/null 2>&1; then
    echo "[seed] installing openssl ..."
    apk add --no-cache openssl >/dev/null
fi

# ---------------------------------------------------------------------------
# 3. Helpers.
# ---------------------------------------------------------------------------
# Check whether a client with the given client_id already exists.
client_exists() {
    _id="$1"
    _code=$(curl -s -o /dev/null -w '%{http_code}' \
        "${HYDRA_ADMIN_URL}/admin/clients/${_id}")
    [ "${_code}" = "200" ]
}

# Delete a client by id (best-effort). Hydra stores only hashed secrets,
# so there is no way to recover the plaintext once it is issued; a re-seed
# always means "regenerate secrets", which in turn means delete-and-create.
client_delete() {
    _id="$1"
    curl -s -o /dev/null -X DELETE "${HYDRA_ADMIN_URL}/admin/clients/${_id}" || true
}

# Register a client. Arguments: client_id, json_payload_file.
# On failure, the (redacted) response is printed so a human can diagnose;
# on success, the response body is discarded.
register_client() {
    _id="$1"
    _body_file="$2"
    _code=$(curl -s -o /tmp/hydra-admin-resp -w '%{http_code}' \
        -H 'Content-Type: application/json' \
        -X POST "${HYDRA_ADMIN_URL}/admin/clients" \
        --data @"${_body_file}")
    if [ "${_code}" != "201" ]; then
        echo "[seed] ERROR: failed to create client ${_id} (HTTP ${_code})" >&2
        cat /tmp/hydra-admin-resp >&2 || true
        rm -f /tmp/hydra-admin-resp
        exit 1
    fi
    rm -f /tmp/hydra-admin-resp
}

# ---------------------------------------------------------------------------
# 4. Build the two client definitions. Secrets are never written to stdout
#    or to any log; only into the protected JSON file.
# ---------------------------------------------------------------------------
CC_ID="gateway-test-cc"
AC_ID="gateway-test-ac"

CC_SECRET="$(openssl rand -hex 24)"
AC_SECRET="$(openssl rand -hex 24)"

CC_BODY="$(mktemp)"
AC_BODY="$(mktemp)"
trap 'rm -f "${CC_BODY}" "${AC_BODY}"' EXIT

cat >"${CC_BODY}" <<EOF
{
  "client_id": "${CC_ID}",
  "client_name": "gateway-core client_credentials test client",
  "client_secret": "${CC_SECRET}",
  "grant_types": ["client_credentials"],
  "response_types": [],
  "scope": "read write",
  "token_endpoint_auth_method": "client_secret_basic",
  "audience": ["gateway-core"]
}
EOF

cat >"${AC_BODY}" <<EOF
{
  "client_id": "${AC_ID}",
  "client_name": "gateway-core authorization_code test client",
  "client_secret": "${AC_SECRET}",
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "scope": "openid email",
  "redirect_uris": ["http://localhost:3000/callback"],
  "token_endpoint_auth_method": "client_secret_basic"
}
EOF

# ---------------------------------------------------------------------------
# 5. Register each client (idempotent).
# ---------------------------------------------------------------------------
if client_exists "${CC_ID}"; then
    echo "[seed] client ${CC_ID} exists - rotating secret (delete + create)"
    client_delete "${CC_ID}"
fi
echo "[seed] creating client ${CC_ID} ..."
register_client "${CC_ID}" "${CC_BODY}"

if client_exists "${AC_ID}"; then
    echo "[seed] client ${AC_ID} exists - rotating secret (delete + create)"
    client_delete "${AC_ID}"
fi
echo "[seed] creating client ${AC_ID} ..."
register_client "${AC_ID}" "${AC_BODY}"

# ---------------------------------------------------------------------------
# 6. Write the output file. Chmod 600 before writing secrets.
# ---------------------------------------------------------------------------
TMP_OUT="$(mktemp)"
cat >"${TMP_OUT}" <<EOF
{
  "issuer": "${HYDRA_PUBLIC_URL}/",
  "token_endpoint": "${HYDRA_PUBLIC_URL}/oauth2/token",
  "jwks_uri": "${HYDRA_PUBLIC_URL}/.well-known/jwks.json",
  "clients": {
    "gateway-test-cc": {
      "client_id": "${CC_ID}",
      "client_secret": "${CC_SECRET}",
      "grant_types": ["client_credentials"],
      "scopes": ["read", "write"],
      "token_endpoint": "${HYDRA_PUBLIC_URL}/oauth2/token"
    },
    "gateway-test-ac": {
      "client_id": "${AC_ID}",
      "client_secret": "${AC_SECRET}",
      "grant_types": ["authorization_code", "refresh_token"],
      "scopes": ["openid", "email"],
      "redirect_uris": ["http://localhost:3000/callback"],
      "token_endpoint": "${HYDRA_PUBLIC_URL}/oauth2/token"
    }
  }
}
EOF

umask 177
mv "${TMP_OUT}" "${OUT_FILE}"
chmod 600 "${OUT_FILE}"

echo "[seed] wrote ${OUT_FILE} (mode 600)"
echo "[seed] done."
