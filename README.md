# LLM Gateway & Observability Layer

A Spring Boot proxy in front of LLM providers with caching, rate limiting, cost tracking, model fallback, prompt versioning, and a metrics dashboard.

See [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) for the roadmap and [DEV_STANDARDS.md](DEV_STANDARDS.md) for coding standards.

## Quickstart

Requires JDK 25, Maven, and Docker.

```bash
# 1. Start infrastructure (Postgres :5433, Redis :6379, Prometheus :9090, Grafana :3000)
cd deploy && docker compose up -d

# 2. Run the gateway (http://localhost:8080)
cd ../backend && mvn spring-boot:run
```

Check it works:

- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/prometheus
- Grafana: http://localhost:3000 (user `admin`, password from `GRAFANA_ADMIN_PASSWORD`, default `change-me`)

## API keys

Every `/v1/**` call needs a gateway key (`Authorization: Bearer gw-...`). Start the gateway with `GATEWAY_ADMIN_KEY` set, then create a key. The raw key is shown **once**; only its SHA-256 hash is stored.

```bash
curl -X POST http://localhost:8080/admin/v1/api-keys \
  -H "Authorization: Bearer $GATEWAY_ADMIN_KEY" -H "Content-Type: application/json" \
  -d '{"owner": "my-app"}'
```

Each request is recorded in the `request_logs` table (model, provider, latency, status, tokens; never prompt text).

Copy `.env.example` to `.env` to override default credentials. Never commit `.env`.
