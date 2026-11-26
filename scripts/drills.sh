# Helpers for the failure drills: https://ffetih.github.io/ff-ecommerce-docs/ff-ecommerce/developer/try-it/failure-drills.html
#
#   source scripts/drills.sh      # once per terminal, from the repository root
#   drill_setup                   # tokens, a product with 10 units, bob's profile
#
# Requires curl, jq, docker compose, and the whole system running.

source "$(dirname "${BASH_SOURCE[0]}")/dev-auth.sh"

GW=${GW:-http://localhost:8080/api/v1}
J='Content-Type: application/json'

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
  curl -s -H "Authorization: Bearer $ADMIN" localhost:8083/actuator/metrics/messaging.dead.letters \
    | jq -c '[.measurements[0].value, .availableTags]'
}

# order-service's outbox: messages waiting for Kafka, and how long the oldest has waited (seconds).
outbox() {
  for m in outbox.pending outbox.oldest.pending.age; do
    printf '%-28s %s\n' $m "$(curl -s -H "Authorization: Bearer $ADMIN" localhost:8083/actuator/metrics/$m | jq '.measurements[0].value')"
  done
}

# Publishes an event as if a service had sent it: send_event <topic> <key> <type> <data-json>
send_event() {
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
