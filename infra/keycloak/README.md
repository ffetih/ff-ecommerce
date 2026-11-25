# Keycloak realm

`import/ecommerce-realm.json` is the whole identity setup for local development and end-to-end
tests. Keycloak imports it when the container is created; there is no manual clicking in the admin
console. To change it, edit the file and recreate the container:

```bash
docker compose up -d --force-recreate keycloak
```

Changes made in the admin console are lost when the container is recreated, on purpose.

## Users

| User | Password | Role | Keycloak id (= customer id) |
| --- | --- | --- | --- |
| alice | alice | `customer` | `a11ce000-0000-4000-8000-000000000001` |
| bob | bob | `customer` | `b0b00000-0000-4000-8000-000000000002` |
| admin | admin | `admin` | `ad000000-0000-4000-8000-000000000003` |

The ids are fixed so that scripts and tests can refer to them.

## Clients

| Client | Type | Used by | Audience in its tokens |
| --- | --- | --- | --- |
| `ecommerce-cli` | public, device authorization grant | people in a terminal | all four services |
| `ecommerce-dev` | public, password grant | `scripts/dev-auth.sh`, end-to-end tests | all four services |
| `order-service` | confidential, client credentials | order-service calling catalog and customer | `catalog-service`, `customer-service` |

`ecommerce-dev` exists only because scripts and tests need a non-interactive login. The password
grant is deprecated by the OAuth 2.0 Security Best Current Practice; a real realm would not have it.

Each service accepts a token only if its own name is in the `aud` claim. An `order-service` token
therefore works against catalog and customer, and is rejected by shipping.

## Roles

Realm roles appear in access tokens under `realm_access.roles`:

- `customer`: shoppers; they can only see and change their own data.
- `admin`: back office.
- `service`: machine identities (service accounts).

## Admin console

http://localhost:8180, user `kcadmin`, password `kcadmin` (master realm). Not to be confused with
the `admin` user of the `ecommerce` realm.
