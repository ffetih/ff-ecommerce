# Token helpers for local development. Load them into your shell:
#
#   source scripts/dev-auth.sh
#
#   token alice                 # access token for a realm user (password = username)
#   service-token order-service # access token from client credentials
#   jwt "$(token alice)"        # print a token's payload (claims), not verified
#
# Requires curl and jq. Uses the DEV-ONLY "ecommerce-dev" client (password grant), which must not
# exist in a real realm. Set KEYCLOAK_URL to point somewhere other than the compose Keycloak.

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-ecommerce}"

_token_endpoint() {
  printf '%s/realms/%s/protocol/openid-connect/token' "$KEYCLOAK_URL" "$KEYCLOAK_REALM"
}

_access_token_or_error() {
  local response="$1" token
  token=$(jq -r '.access_token // empty' <<<"$response")
  if [[ -z "$token" ]]; then
    echo "token request failed: $response" >&2
    return 1
  fi
  printf '%s\n' "$token"
}

token() {
  local user="${1:?usage: token <username> [password]}" password="${2:-$1}"
  _access_token_or_error "$(curl -s -X POST "$(_token_endpoint)" \
    -d grant_type=password -d client_id=ecommerce-dev \
    --data-urlencode "username=$user" --data-urlencode "password=$password")"
}

service-token() {
  local client="${1:?usage: service-token <client-id> [secret]}" secret="${2:-$1-secret}"
  _access_token_or_error "$(curl -s -X POST "$(_token_endpoint)" \
    -d grant_type=client_credentials \
    --data-urlencode "client_id=$client" --data-urlencode "client_secret=$secret")"
}

jwt() {
  local payload
  payload=$(cut -d. -f2 <<<"${1:?usage: jwt <token>}" | tr '_-' '/+')
  while (( ${#payload} % 4 )); do payload+='='; done
  base64 -d <<<"$payload" | jq .
}
