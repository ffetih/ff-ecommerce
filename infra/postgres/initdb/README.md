# PostgreSQL init scripts

`*.sql` and `*.sh` files in this folder run once, in alphabetical order, when the
`postgres-data` volume is created for the first time. Each service sprint adds a script that
creates its own database and user (see ADR-0002).

To re-run the scripts after adding one, recreate the volume (this deletes local data):

```bash
docker compose down -v
docker compose up -d
```
