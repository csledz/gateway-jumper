<!--
SPDX-FileCopyrightText: 2023 Deutsche Telekom AG

SPDX-License-Identifier: CC0-1.0
-->

# gateway-core test environment

This document describes how to bring up the shared test landscape used
by all `gateway-core/*` sibling modules for integration testing. The
landscape is driven by the top-level `docker-compose.yml` and consists
of the following services:

| Service         | Purpose                                   | Host ports      |
|-----------------|-------------------------------------------|-----------------|
| `jaeger`        | OTLP / Zipkin trace collector + UI        | 16686, 4317/18  |
| `prometheus`    | Metrics scraper for the gateway           | 9090            |
| `echo`          | Upstream HTTP echo for route tests        | 8081            |
| `redis`         | Shared state for rate-limiter etc.        | 6379            |
| `hydra-db`      | PostgreSQL backing store for Hydra        | —               |
| `hydra-migrate` | One-shot SQL migration for Hydra          | —               |
| `hydra`         | Ory Hydra OAuth2 / OIDC provider          | 4444, 4445      |
| `hydra-seed`    | One-shot init that seeds two OAuth2 apps  | —               |

All services share a single docker network (`jaeger`).

## Bringing the landscape up

Hydra needs a system secret. The compose file refuses to start without
it — that is intentional so no default sneaks into the repo:

```bash
export HYDRA_SYSTEM_SECRET=$(openssl rand -hex 32)
docker-compose up -d
```

Wait for Hydra to become healthy (around 15–30 seconds). You can watch
it with:

```bash
docker-compose ps hydra
docker-compose logs -f hydra-seed
```

Once `hydra-seed` exits successfully, the file
`docker-support/hydra/secrets/hydra-clients.json` contains two seeded
clients with runtime-generated secrets. The path is `.gitignore`d.

## OIDC endpoints

| Endpoint            | URL                                                           |
|---------------------|---------------------------------------------------------------|
| Issuer              | `http://localhost:4444/`                                      |
| Discovery           | `http://localhost:4444/.well-known/openid-configuration`      |
| JWKS                | `http://localhost:4444/.well-known/jwks.json`                 |
| Token endpoint      | `http://localhost:4444/oauth2/token`                          |
| Admin API           | `http://localhost:4445/admin/`                                |
| Health (public)     | `http://localhost:4444/health/ready`                          |

Smoke check:

```bash
curl -sf http://localhost:4444/.well-known/openid-configuration | jq .issuer
# => "http://localhost:4444/"
```

## Seeded OAuth2 clients

| Alias             | Grant types                         | Scopes        | Auth method            |
|-------------------|-------------------------------------|---------------|------------------------|
| `gateway-test-cc` | `client_credentials`                | `read write`  | `client_secret_basic`  |
| `gateway-test-ac` | `authorization_code refresh_token`  | `openid email`| `client_secret_basic`  |

The `gateway-test-ac` client has `redirect_uris = http://localhost:3000/callback`.
Tests that drive the authorization_code flow are expected to skip the
UI and use Hydra's admin API (`/admin/oauth2/auth/requests/login/accept`
and `/admin/oauth2/auth/requests/consent/accept`) to complete the dance
headlessly. The consent UI is **out of scope** of the test landscape.

## Using Hydra from a sibling module's Cucumber step

Add the dependency:

```xml
<dependency>
  <groupId>io.telekom.gateway</groupId>
  <artifactId>testkit-hydra</artifactId>
  <version>0.0.0</version>
  <scope>test</scope>
</dependency>
```

Point the library at the seed file (once per shell):

```bash
export HYDRA_CLIENTS_FILE=$(pwd)/docker-support/hydra/secrets/hydra-clients.json
```

Then in a step:

```java
import io.cucumber.java.en.When;
import io.telekom.gateway.testkit.hydra.HydraClient;
import java.util.List;

public class AuthSteps {
    private final HydraClient hydra = new HydraClient();
    private String authHeader;

    @When("the caller obtains a client_credentials token")
    public void obtainToken() {
        String accessToken = hydra
            .clientCredentialsToken("gateway-test-cc", List.of("read"))
            .block();
        this.authHeader = "Bearer " + accessToken;
        // Never log `accessToken`.
    }
}
```

See `gateway-core/testkit-hydra/README.md` for the JUnit 5 extension
form (`@ExtendWith(HydraFixture.class)`).

## Troubleshooting

### `HYDRA_SYSTEM_SECRET must be set`

`docker-compose` refused to substitute the variable. Export it in the
shell you run `docker-compose` from:

```bash
export HYDRA_SYSTEM_SECRET=$(openssl rand -hex 32)
```

### `hydra-seed` exits with `ERROR: Hydra admin API did not become ready in 60s`

Hydra's SQL migration probably failed. Check:

```bash
docker-compose logs hydra-migrate
docker-compose logs hydra
```

Common cause: stale `hydra-db` volume from a previous (incompatible)
Hydra version. Nuke it:

```bash
docker-compose down -v
docker-compose up -d
```

### `HydraClient` fails with "hydra clients file not readable"

The seed file has not been written yet, or `HYDRA_CLIENTS_FILE` points
at the wrong path. Verify:

```bash
docker-compose ps hydra-seed     # should be "exited (0)"
ls -la docker-support/hydra/secrets/hydra-clients.json
```

If the file exists but the test still cannot read it, the test process
is running as a user without access — `chmod 600` restricts reads to
the file owner. Run the tests as the same user that brought up
docker-compose.

### Token exchange returns HTTP 401

Double-check the alias you passed (`gateway-test-cc` vs
`gateway-test-ac`). The `authorization_code` client cannot mint tokens
via the `client_credentials` grant.

### Re-seeding

Each run of `hydra-seed` deletes and re-creates the two clients, so the
secrets in `hydra-clients.json` are always fresh and internally
consistent. To trigger a rotation without nuking the whole landscape:

```bash
docker-compose up --force-recreate hydra-seed
```

For a completely clean slate (new keys, new DB, new secrets):

```bash
docker-compose down -v
rm -rf docker-support/hydra/secrets/
export HYDRA_SYSTEM_SECRET=$(openssl rand -hex 32)
docker-compose up -d
```
