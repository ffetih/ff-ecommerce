#!/usr/bin/env bash
# The local Kubernetes cluster for s12 (ADR-0013): k3d, i.e. k3s running in Docker.
#
#   scripts/cluster.sh create    create the cluster (compose must be stopped: same memory, same ports)
#   scripts/cluster.sh delete    delete it; Flux rebuilds everything after a new bootstrap
#   scripts/cluster.sh stop|start
#
# Everything inside the cluster comes from deploy/ through Flux; this script only makes the empty
# cluster and its one entry point.
set -euo pipefail

NAME=ff-ecommerce
# Pinned: Flux 2.10 needs Kubernetes 1.35.2 or newer.
K3S_IMAGE=rancher/k3s:v1.35.5-k3s1
# All web entry points share one host port; Traefik routes by hostname (*.localtest.me -> 127.0.0.1):
#   http://shop.localtest.me:8888       the gateway (the API)
#   http://keycloak.localtest.me:8888   Keycloak (the token issuer)
#   http://grafana.localtest.me:8888, jaeger.…, prometheus.…, mail.…   operations
HTTP_PORT=8888

case "${1:-}" in
  create)
    k3d cluster create "$NAME" \
      --image "$K3S_IMAGE" \
      --servers 1 --agents 0 \
      --port "${HTTP_PORT}:80@loadbalancer" \
      --wait
    kubectl config use-context "k3d-$NAME"
    kubectl get nodes -o wide
    ;;
  delete) k3d cluster delete "$NAME" ;;
  stop)   k3d cluster stop "$NAME" ;;
  start)  k3d cluster start "$NAME" && kubectl config use-context "k3d-$NAME" ;;
  *) echo "usage: $0 create|delete|stop|start" >&2; exit 2 ;;
esac
