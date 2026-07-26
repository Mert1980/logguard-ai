# Running LogGuard against a production OpenSearch cluster

By default LogGuard reads from the local Docker OpenSearch (`http://localhost:9200`, plain HTTP, no auth —
Story 1.1). To point it at a secured production cluster (e.g. an OpenShift OpenSearch with the security
plugin enabled) you switch **entirely via environment variables** — no code change, no profile files, and the
local Docker path keeps working when the variables are unset.

## What you need to obtain first

1. **Endpoint URL** — `https://<host>:<port>` of the production cluster.
2. **A read-only service account** — username + password with `search`/`read` permission on the index
   pattern `logstash-app-openshift-application-springboot_error_*`.
3. **Network reachability** — VPN and/or firewall access from wherever LogGuard runs to that endpoint.
4. **TLS trust** — does the cluster's HTTPS certificate chain to a **public CA** (nothing extra needed) or a
   **private/self-signed CA** (you need the CA certificate to build a truststore)? OpenShift routes are often
   signed by a private CA.

Verify access before starting the app:

```bash
curl -u <user>:<pass> https://<host>:<port>/_cat/indices
# add --cacert <ca.pem> if the cluster uses a private CA
```

## Environment variables

| Variable | Purpose | Example |
|---|---|---|
| `LOGGUARD_OPENSEARCH_BASE_URL` | Cluster URL (use `https://` to enable TLS) | `https://opensearch.prod.example:9200` |
| `LOGGUARD_OPENSEARCH_USERNAME` | Basic-auth user (blank = no auth) | `svc-logguard` |
| `LOGGUARD_OPENSEARCH_PASSWORD` | Basic-auth password | *(secret)* |
| `LOGGUARD_OPENSEARCH_INDEX_PATTERN` | Override the index pattern if prod differs | `logstash-app-openshift-application-springboot_error_*` |
| `LOGGUARD_OPENSEARCH_TRUSTSTORE_PATH` | Path to a `.p12`/`.jks` holding the private CA cert (blank = JDK default trust) | `/etc/logguard/opensearch-ca.p12` |
| `LOGGUARD_OPENSEARCH_TRUSTSTORE_PASSWORD` | Truststore password | *(secret)* |

Basic-auth wiring activates when a username is set; TLS wiring activates when the URL is `https://`. The
connect/socket timeouts (`logguard.opensearch.connect-timeout` / `socket-timeout`, defaults 5s/10s) apply on
the https transport so a hung endpoint can't stall a poll cycle.

### Building a truststore for a private CA

```bash
keytool -importcert -alias opensearch-ca -file ca.pem \
  -keystore opensearch-ca.p12 -storetype PKCS12 -storepass <pw> -noprompt
```

## Example run

```bash
export LOGGUARD_OPENSEARCH_BASE_URL="https://opensearch.prod.example:9200"
export LOGGUARD_OPENSEARCH_USERNAME="svc-logguard"
export LOGGUARD_OPENSEARCH_PASSWORD="…"
# only if the cluster uses a private CA:
export LOGGUARD_OPENSEARCH_TRUSTSTORE_PATH="/etc/logguard/opensearch-ca.p12"
export LOGGUARD_OPENSEARCH_TRUSTSTORE_PASSWORD="…"

java -jar target/logguard-ai-0.0.1-SNAPSHOT.jar
```

Unset the variables (or start a fresh shell) to return to the local Docker instance.

## Notes

- **Never commit credentials.** Supply them via the environment or a secret manager, and keep them out of
  shell history. Use a read-only account.
- **First run against prod** replays a look-back window; prod volume can be far higher than the local fixture
  and each poll is capped at 1000 results — consider a short look-back for the first run.
- The gated integration test `OpenSearchAdapterLiveTest` can be pointed at prod with `-Dopensearch.live=true`
  for an isolated connectivity check.
