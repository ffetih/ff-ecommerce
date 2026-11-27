# deploy/

Everything that runs in the Kubernetes cluster, declared in Git and kept true by Flux (ADR-0013).

| Folder | What |
| --- | --- |
| `clusters/local/` | What Flux syncs for the local k3d cluster: its own components (`flux-system/`, written by `flux bootstrap`) and one Kustomization per layer |
| `infrastructure/` | Operators and shared services: Strimzi (Kafka), CloudNativePG (PostgreSQL), Keycloak, kube-prometheus-stack, Jaeger, Mailpit |
| `apps/base/` | The six services: Deployment, Service, probes, resources |
| `apps/overlays/local/` | This cluster: image tags, hostnames, credentials |

Create the cluster with `scripts/cluster.sh create`, then bootstrap Flux once; from then on a `git push` is a deployment.
