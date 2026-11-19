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

Copy `.env.example` to `.env` to override default credentials. Never commit `.env`.
