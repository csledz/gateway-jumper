<!--
SPDX-FileCopyrightText: 2023 Deutsche Telekom AG

SPDX-License-Identifier: CC0-1.0
-->

# testkit-hydra

Tiny test utility library that obtains OAuth2 access tokens from the
Ory Hydra IdP running in the gateway-core docker-compose test
landscape. It is used by sibling modules' Cucumber / JUnit 5
integration tests so they can authenticate against a *real* IdP rather
than a MockServer stub.

## Design goals

- Zero Spring Boot — plain Java 21 + reactor-netty + Jackson + SLF4J.
- Reactive API (`Mono<String>`) — plays nicely with the rest of
  gateway-core.
- No secret literals in the repo. The seed file is generated at
  container startup by `hydra-seed`; this library only *reads* it.
- Tokens are never logged, persisted, or echoed through exception
  messages.

## Environment contract

The module reads a JSON seed file that `hydra-seed` writes to a shared
docker volume. The path is resolved in this order:

1. constructor argument to `HydraClient(Path)`
2. the `HYDRA_CLIENTS_FILE` environment variable
3. the default `/secrets/hydra-clients.json` (inside the container);
   locally, bind-mount the file or override the env var

Seed-file layout:

```json
{
  "issuer": "http://localhost:4444/",
  "token_endpoint": "http://localhost:4444/oauth2/token",
  "jwks_uri": "http://localhost:4444/.well-known/jwks.json",
  "clients": {
    "gateway-test-cc": {
      "client_id": "gateway-test-cc",
      "client_secret": "<generated at runtime>",
      "grant_types": ["client_credentials"],
      "scopes": ["read", "write"],
      "token_endpoint": "http://localhost:4444/oauth2/token"
    },
    "gateway-test-ac": {
      "client_id": "gateway-test-ac",
      "client_secret": "<generated at runtime>",
      "grant_types": ["authorization_code", "refresh_token"],
      "scopes": ["openid", "email"],
      "redirect_uris": ["http://localhost:3000/callback"],
      "token_endpoint": "http://localhost:4444/oauth2/token"
    }
  }
}
```

The file is written by `docker-support/hydra/seed-clients.sh` with
mode `600`. The parent directory
(`docker-support/hydra/secrets/`) is `.gitignore`d so nothing leaks
into the repo.

## Quick start

```bash
export HYDRA_SYSTEM_SECRET=$(openssl rand -hex 32)
docker-compose up -d hydra-db hydra-migrate hydra hydra-seed
export HYDRA_CLIENTS_FILE=$(pwd)/docker-support/hydra/secrets/hydra-clients.json
./mvnw -pl gateway-core/testkit-hydra verify
```

## Using the client in a Cucumber step

```java
import io.telekom.gateway.testkit.hydra.HydraClient;
import java.util.List;

public class GatewayCoreSteps {
    private final HydraClient hydra = new HydraClient();

    @When("the client is authenticated against Hydra")
    public void authenticate() {
        String token = hydra
            .clientCredentialsToken("gateway-test-cc", List.of("read"))
            .block();
        // attach `token` to outgoing request as Bearer; never log it
        this.authHeader = "Bearer " + token;
    }
}
```

## Using the JUnit 5 extension

```java
import io.telekom.gateway.testkit.hydra.HydraClient;
import io.telekom.gateway.testkit.hydra.HydraFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(HydraFixture.class)
class MyGatewayIntegrationTest {
    private HydraClient hydra;   // injected

    @Test
    void token_flow() {
        String at = hydra
            .clientCredentialsToken("gateway-test-cc", List.of("read"))
            .block();
        // assert on response downstream, never on `at`
    }
}
```

## Discovery

```java
HydraDiscovery discovery = new HydraDiscovery("http://localhost:4444/");
String tokenEndpoint = discovery.tokenEndpoint().block();
String jwks = discovery.jwksUri().block();
```

## Safety notes

- Do **not** add `log.info(token)` anywhere downstream — logging of
  access tokens is forbidden by the security rules that govern this
  landscape.
- The seed file is rewritten by `hydra-seed` on every fresh compose
  start. Persisted local copies will drift; treat the file as
  single-writer.
- Secrets for the two seeded clients are generated inside the init
  container with `openssl rand -hex 24`. If you need to rotate them,
  bring the landscape down and up again (`docker-compose down -v`).
