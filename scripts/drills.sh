# Helpers for the failure drills: https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/try-it/failure-drills.html
#
#   source scripts/drills.sh                       # once per terminal, from the repository root
#   DRILL_TARGET=k8s source scripts/drills.sh      # the same, against the k3d cluster (s12)
#   drill_setup                                    # tokens, a product with 10 units, bob's profile
#
# Requires curl, jq, and the whole system running: docker compose (default), or kubectl for k8s.

# Where the system runs. The addresses are the only difference for most helpers; the few that read
# a database or an actuator endpoint directly go through _psql and _actuator below.
# Kept in a variable of our own: with "DRILL_TARGET=k8s source …" bash drops DRILL_TARGET (and any
# assignment to it) as soon as source returns, but the helpers run later.
_drill_target=${DRILL_TARGET:-compose}
if [[ $_drill_target == k8s ]]; then
  KEYCLOAK_URL=${KEYCLOAK_URL:-http://keycloak.localtest.me:8888}
  GW=${GW:-http://shop.localtest.me:8888/api/v1}
  JAEGER=${JAEGER:-http://jaeger.localtest.me:8888}
fi

source "$(dirname "${BASH_SOURCE[0]}")/dev-auth.sh"

GW=${GW:-http://localhost:8080/api/v1}
JAEGER=${JAEGER:-http://localhost:16686}
J='Content-Type: application/json'

# psql in one service's database: _psql <db> <psql options...>. Compose logs in as the service's own
# user; in the cluster kubectl exec runs psql as postgres inside the CloudNativePG pod.
_psql() {
  local db=$1; shift
  if [[ $_drill_target == k8s ]]; then
    kubectl exec -i -n ecommerce postgres-1 -c postgres -- psql -U postgres -d "$db" "$@"
  else
    local user=${db%_db}_service
    docker compose exec -T -e PGPASSWORD="$user" postgres psql -h localhost -U "$user" -d "$db" "$@"
  fi
}

# GET an actuator path of order-service as admin: _actuator <path>. In the cluster the service has no
# address outside, so a short-lived port-forward stands in for localhost:8083.
_actuator() {
  if [[ $_drill_target == k8s ]]; then
    kubectl port-forward -n ecommerce svc/order-service 18083:8083 >/dev/null 2>&1 &
    local pf=$!
    sleep 1
    curl -s -H "Authorization: Bearer $ADMIN" "localhost:18083/actuator/$1"
    kill $pf 2>/dev/null; wait $pf 2>/dev/null
  else
    curl -s -H "Authorization: Bearer $ADMIN" "localhost:8083/actuator/$1"
  fi
}

uuid() { cat /proc/sys/kernel/random/uuid; }

# New tokens; they live 15 minutes, so run again after a 401.
fresh() { ADMIN=$(token admin); BOB=$(token bob); }

# Tokens, a product with 10 units in $SKU, and bob's profile (409 if it exists, which is fine).
drill_setup() {
  fresh
  SKU=DRILL-$RANDOM
  curl -s -X POST $GW/products -H "Authorization: Bearer $ADMIN" -H "$J" \
    -d "{\"sku\":\"$SKU\",\"name\":\"Drill\",\"unitPrice\":5.00,\"currency\":\"EUR\"}" > /dev/null
  curl -s -X POST $GW/stock/$SKU/receipts -H "Authorization: Bearer $ADMIN" -H "$J" \
    -d "{\"referenceId\":\"$(uuid)\",\"quantity\":10}" > /dev/null
  curl -s -X POST $GW/customers/me -H "Authorization: Bearer $BOB" -H "$J" \
    -d '{"fullName":"Bob Example","shippingAddress":"Kadıköy 1, İstanbul"}' > /dev/null
  echo "SKU = $SKU, stock = $(curl -s $GW/stock/$SKU -H "Authorization: Bearer $ADMIN" | jq -c '{onHand,reserved,available}')"
}

# Places one unit of $SKU as bob, waits for the saga, prints the order id.
# place [card-token]: tok_ok (default), tok_decline, tok_slow or tok_capture_fails picks the payment outcome.
place() {
  curl -s -X POST $GW/orders -H "Authorization: Bearer $BOB" -H "$J" -H "Idempotency-Key: $(uuid)" \
    -d "{\"cardToken\":\"${1:-tok_ok}\",\"lines\":[{\"sku\":\"$SKU\",\"quantity\":1}]}" | jq -r .id
  sleep 2
}

# Order, shipment and stock of one order.
show() {
  echo "order    = $(curl -s $GW/orders/$1 -H "Authorization: Bearer $BOB" | jq -r '"\(.status), payment \(.paymentStatus)"')"
  echo "shipment = $(curl -s "$GW/shipments?orderId=$1" -H "Authorization: Bearer $ADMIN" | jq -r 'if .id then .status else "none" end')"
  echo "stock    = $(curl -s $GW/stock/$SKU -H "Authorization: Bearer $ADMIN" | jq -c '{onHand,reserved,available}')"
}

# order-service's dead-letter counter: [count, tags].
dl() {
  _actuator metrics/messaging.dead.letters | jq -c '[.measurements[0].value, .availableTags]'
}

# order-service's outbox: messages waiting for Kafka, and how long the oldest has waited (seconds).
outbox() {
  for m in outbox.pending outbox.oldest.pending.age; do
    printf '%-28s %s\n' $m "$(_actuator metrics/$m | jq '.measurements[0].value')"
  done
}

# The Jaeger link of an order's trace: trace <order>. The trace id is the second part of the
# traceparent order-service stored with the order's first event (compose: needs the observability profile).
trace() {
  local id
  id=$(_psql order_db -tAc \
    "SELECT split_part(traceparent,'-',2) FROM outbox_messages WHERE message_key='$1' ORDER BY id LIMIT 1")
  echo "$JAEGER/trace/${id:-none}"
}

# Publishes an event as if a service had sent it: send_event <topic> <key> <type> <data-json>
# Compose only: in the cluster every Kafka client needs a KafkaUser with ACLs, and no drill user exists.
send_event() {
  if [[ $_drill_target == k8s ]]; then
    echo "send_event works with compose only: the cluster's Kafka requires a KafkaUser" >&2
    return 1
  fi
  local msg
  msg=$(jq -cn --arg id "$(uuid)" --arg type "$3" --argjson data "$4" \
    '{eventId:$id, eventType:$type, eventVersion:1, occurredAt:(now|todate), source:"drill", data:$data}')
  echo "$2|$msg" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$1" --property parse.key=true --property key.separator='|'
}

# One round of the cancellation race; the optional argument delays the dispatch (seconds).
race() {
  ORDER=$(place)
  SHIP=$(curl -s "$GW/shipments?orderId=$ORDER" -H "Authorization: Bearer $ADMIN" | jq -r .id)
  curl -s -o /dev/null -w 'cancel   -> %{http_code}\n' -X POST $GW/orders/$ORDER/cancel \
    -H "Authorization: Bearer $BOB" -H "$J" -d '{"reason":"race"}' &
  (sleep "${1:-0}"; curl -s -o /dev/null -w 'dispatch -> %{http_code}\n' -X POST $GW/shipments/$SHIP/dispatch \
    -H "Authorization: Bearer $ADMIN") &
  wait; sleep 3
  show $ORDER
}

# Cancels an order as bob and prints the HTTP status.
cancel() {
  curl -s -o /dev/null -w 'cancel   -> %{http_code}\n' -X POST $GW/orders/$1/cancel \
    -H "Authorization: Bearer $BOB" -H "$J" -d '{"reason":"changed my mind"}'
}

# Dispatches an order's shipment as the warehouse and prints the HTTP status.
dispatch() {
  local ship
  ship=$(curl -s "$GW/shipments?orderId=$1" -H "Authorization: Bearer $ADMIN" | jq -r .id)
  curl -s -o /dev/null -w 'dispatch -> %{http_code}\n' -X POST $GW/shipments/$ship/dispatch \
    -H "Authorization: Bearer $ADMIN"
}

# payment-service's view of an order: the payment row, its ledger entries and its refunds (no API there).
payment() {
  _psql payment_db -q \
    -c "select status, void_requested, provider_reference is not null as held, refunded_amount, failure_reason from payments where order_id = '$1'" \
    -c "select t.kind, e.account, e.amount, e.currency from ledger_transactions t
          join ledger_entries e on e.transaction_id = t.id join payments p on p.id = t.payment_id
         where p.order_id = '$1' order by t.booked_at, e.line_number" \
    -c "select r.status, r.amount, r.failure_reason from refunds r where r.order_id = '$1' order by r.created_at"
}

# --- returns (s09) ---------------------------------------------------------------------------

# Places an order for <quantity> units of $SKU as bob, ships and delivers it, prints the order id.
delivered() {
  local order ship
  order=$(curl -s -X POST $GW/orders -H "Authorization: Bearer $BOB" -H "$J" -H "Idempotency-Key: $(uuid)" \
    -d "{\"cardToken\":\"${2:-tok_ok}\",\"lines\":[{\"sku\":\"$SKU\",\"quantity\":${1:-1}}]}" | jq -r .id)
  sleep 2
  ship=$(curl -s "$GW/shipments?orderId=$order" -H "Authorization: Bearer $ADMIN" | jq -r .id)
  curl -s -o /dev/null -X POST $GW/shipments/$ship/dispatch -H "Authorization: Bearer $ADMIN"
  sleep 2
  curl -s -o /dev/null -X POST $GW/shipments/$ship/deliver -H "Authorization: Bearer $ADMIN"
  sleep 1
  echo $order
}

# Asks to return <quantity> units of $SKU of an order as bob: prints the HTTP status and the return id.
ret() {
  curl -s -w ' %{http_code}' -X POST $GW/orders/$1/returns -H "Authorization: Bearer $BOB" -H "$J" \
    -H "Idempotency-Key: $(uuid)" -d "{\"lines\":[{\"sku\":\"$SKU\",\"quantity\":${2:-1}}],\"reason\":\"drill\"}" \
    | sed -E 's/^(.*) ([0-9]{3})$/\2 \1/' | { read -r code body; echo "return   -> $code $(echo "$body" | jq -r '.id // .detail')"; }
}

# The back office receives a returned parcel: prints the HTTP status and the return's status.
receive() {
  curl -s -w ' %{http_code}' -X POST $GW/returns/$1/receive -H "Authorization: Bearer $ADMIN" \
    | sed -E 's/^(.*) ([0-9]{3})$/\2 \1/' | { read -r code body; echo "receive  -> $code $(echo "$body" | jq -r '.detail // .status')"; }
}

# An order's returns and how many units of each SKU they hold.
returns() {
  curl -s $GW/orders/$1/returns -H "Authorization: Bearer $BOB" \
    | jq -c '{returns: [.returns[] | {id: .id[0:8], status, refund: .refundAmount}], returnedUnits}'
}
