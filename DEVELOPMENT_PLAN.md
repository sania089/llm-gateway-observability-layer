# LLM Gateway & Observability Layer: Development Plan

A Spring Boot proxy in front of LLM providers with caching, rate limiting, cost tracking, model fallback, prompt versioning, and a metrics dashboard.

**Timeline:** 14 days, about 6-8 focused hours per day. Every day ends with something runnable, tested, and committed.
**Rule:** No feature is cut. If a day slips, the stretch items in Day 13 absorb it, not the core features.

---

## 1. Objective

| Problem | Gateway feature |
|---|---|
| Unbounded, invisible LLM spend | Cost tracking, per-key budgets |
| Provider outages and throttling | Fallback chain, retries, circuit breaker |
| Paying repeatedly for identical prompts | Response cache (exact + semantic) |
| One user or service starving the others | Rate limiting per API key |
| Prompts hardcoded as strings | Versioned prompt registry with rollback |
| No visibility into latency, errors, tokens | Metrics, Prometheus, Grafana, custom dashboard |
| Provider keys scattered everywhere | Gateway-issued keys; provider keys stay server-side |

## 2. Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Language / Framework | Java 25 LTS, Spring Boot 3.x (MVC + virtual threads) | Simple blocking-style code that scales to thousands of concurrent calls |
| HTTP client | Spring `RestClient` (sync) + streaming via `SseEmitter` | No reactive complexity |
| Cache + rate limit state | Redis | Shared state across instances, atomic ops |
| Rate limiting | Bucket4j (Redis backend) | Proven token-bucket implementation |
| Resilience | Resilience4j | Circuit breaker, retry, timeout |
| Database | PostgreSQL + Flyway migrations | Logs, keys, prompts, pricing |
| Metrics | Micrometer, Prometheus, Grafana | Standard stack |
| Tracing | OpenTelemetry (stretch if short on time) | Request correlation |
| Frontend | React + Vite + TypeScript + Recharts + TanStack Query | Fast to build, easy to read |
| Testing | JUnit 5, Mockito, Testcontainers, WireMock, k6 | Real Redis/Postgres in tests, fake providers |
| DevOps | Docker, Docker Compose, GitHub Actions | Reproducible setup, CI |

## 3. Architecture

```
Client App ──► [Auth filter] ──► [Rate limiter] ──► [Cache lookup]
                                                        │ miss
                                                        ▼
                                              [Prompt resolver]
                                                        ▼
                                          [Router + Fallback chain]
                                         ┌──────────┼──────────┐
                                      OpenAI    Anthropic    Gemini ...
                                         └──────────┼──────────┘
                                                        ▼
                              [Usage + cost calculator] ──► async log to Postgres
                                                        ▼
                                          [Cache store] ──► response
Metrics: Micrometer ──► Prometheus ──► Grafana + React dashboard (via admin API)
```

The gateway exposes an **OpenAI-compatible** endpoint (`POST /v1/chat/completions`), so existing SDKs work by changing only the base URL.

## 4. Target Folder Structure

```
llm-gateway/
├── backend/
│   ├── src/main/java/com/gateway/
│   │   ├── GatewayApplication.java
│   │   ├── config/            # Spring config, properties
│   │   ├── common/            # errors, utils, shared DTOs
│   │   ├── auth/              # API keys, auth filter
│   │   ├── proxy/             # controller + request pipeline
│   │   ├── provider/          # ProviderClient interface + per-provider impls
│   │   ├── routing/           # model routing, fallback, circuit breaker
│   │   ├── cache/             # exact + semantic cache
│   │   ├── ratelimit/         # limits + budgets
│   │   ├── cost/              # pricing, token counting, cost calculation
│   │   ├── prompt/            # prompt registry + versions
│   │   ├── usage/             # request logs, async writer
│   │   ├── metrics/           # Micrometer meters
│   │   └── admin/             # dashboard/admin REST API
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/      # Flyway SQL
│   └── src/test/
├── frontend/
│   └── src/ (pages, components, api, hooks, lib)
├── deploy/
│   ├── docker-compose.yml
│   ├── prometheus.yml
│   └── grafana/ (provisioned dashboards)
├── load-tests/                # k6 scripts
├── docs/                      # ADRs, API docs, architecture diagram
├── .github/workflows/ci.yml
├── DEVELOPMENT_PLAN.md
├── DEV_STANDARDS.md
└── README.md
```

Packages are organized **by feature**, not by layer. Each feature folder owns its controller, service, repository, and DTOs.

---

## 5. Day-wise Plan

### Day 1: Foundation and DevOps skeleton
- [x] Create monorepo layout, `.gitignore`, `.editorconfig`, README stub
- [x] Spring Boot project (Java 25 LTS, Maven or Gradle), enable virtual threads
- [x] `docker-compose.yml`: Postgres, Redis, Prometheus, Grafana
- [x] Flyway set up with an initial migration
- [x] Global exception handler returning one consistent error JSON (OpenAI-style error shape)
- [x] `/actuator/health` and `/actuator/prometheus` exposed
- [x] GitHub Actions: build + test on every push
- [x] Config via `application.yml` + env vars (no secrets in the repo)

**Done when:** `docker compose up` brings the stack up; CI is green; health endpoint works.

### Day 2: Provider abstraction + first provider (non-streaming)
- [ ] Define `ProviderClient` interface (`chat(request) -> response`, `supports(model)`)
- [ ] Internal request/response models (OpenAI-compatible schema)
- [ ] Implement OpenAI provider with `RestClient`, timeouts, and error mapping
- [ ] `POST /v1/chat/completions` controller (thin) → proxy service
- [ ] WireMock-based provider tests

**Done when:** a curl to the gateway returns a real completion from OpenAI.

### Day 3: Second provider + streaming
- [ ] Anthropic provider: translate to and from the internal schema
- [ ] SSE streaming pass-through (`stream: true`) with correct chunk forwarding and client-disconnect handling
- [ ] Capture token usage at end of stream (needed for cost on Day 5)
- [ ] Provider registry: resolve provider by model name

**Done when:** both providers work, streaming and non-streaming, through one endpoint.

### Day 4: Auth, API keys, request logging
- [ ] Tables: `api_keys` (hashed key, owner, status, limits), `request_logs`
- [ ] API key generation (show once, store only a SHA-256 hash)
- [ ] Auth filter (`Authorization: Bearer gw-...`)
- [ ] Async request logger (bounded queue + batch insert) that never blocks the request path
- [ ] Log: key, model, provider, latency, status, tokens, cache hit, fallback used, error
- [ ] Indexes on `created_at`, `api_key_id`, `model`

**Done when:** every request is authenticated and appears in `request_logs`.

### Day 5: Cost tracking
- [ ] `model_pricing` table (input/output price per 1M tokens, effective-from date)
- [ ] `CostCalculator`: tokens × price, stored per request in micro-dollars (integer, not float)
- [ ] Token estimation fallback when the provider returns no usage
- [ ] Aggregation queries: cost by key / model / day
- [ ] Unit tests for cost math including edge cases

**Done when:** every logged request has an accurate cost, verified against a known sample.

### Day 6: Response caching
- [ ] Deterministic cache key: hash of normalized (model, messages, temperature, params)
- [ ] Redis exact-match cache with per-key TTL config
- [ ] Skip cache for streaming-by-default? Decide and document: cache complete streams and replay them as SSE
- [ ] Respect `Cache-Control: no-cache` and a `x-gateway-cache: HIT|MISS` response header
- [ ] Cache hits cost $0 and are logged as such
- [ ] Cache hit-rate metric

**Done when:** a repeated request returns in milliseconds, is logged as a hit, and costs $0.

### Day 7: Rate limiting and budgets
- [ ] Bucket4j + Redis token bucket per API key (requests/min)
- [ ] Token-per-minute limit (uses estimated tokens before, actual after)
- [ ] Monthly and daily spend budget per key (blocks when exceeded)
- [ ] `429` with `Retry-After` and `x-ratelimit-*` headers
- [ ] Limits configurable per key via admin API
- [ ] Concurrency test: parallel requests respect limits exactly

**Done when:** limits and budgets are enforced correctly under parallel load.

### Day 8: Fallback, retries, circuit breaker
- [ ] Routing config: model → ordered provider/model fallback chain (YAML + DB override)
- [ ] Retry with backoff only on retryable errors (429, 5xx, timeouts), never on 4xx
- [ ] Resilience4j circuit breaker per provider (opens on failure rate, half-open probing)
- [ ] Fallback to the next model, with the response tagged `x-gateway-fallback: gpt-4o→claude-sonnet`
- [ ] Per-provider timeout settings
- [ ] Tests that simulate provider failure, slowness, and recovery with WireMock

**Done when:** killing a provider mock makes traffic flow to the fallback with no client error.

### Day 9: Prompt versioning
- [ ] Tables: `prompts` (name), `prompt_versions` (version, template, variables, created_by, created_at), `prompt_aliases` (e.g. `prod`, `staging` → version)
- [ ] CRUD API: create a prompt, add a version, promote an alias, roll back
- [ ] Request can reference `{"prompt": "support-bot@prod", "variables": {...}}`
- [ ] Template rendering with variable validation (reject missing variables)
- [ ] Log the prompt name and version on every request
- [ ] Per-version stats: avg cost, latency, error rate (compare version A vs B)

**Done when:** changing the `prod` alias changes behavior with no deploy, and stats are queryable per version.

### Day 10: Metrics and observability
- [ ] Micrometer meters: request count, latency histogram, tokens, cost, cache hits, fallbacks, rate-limit rejections, circuit state, tagged by model/provider/key
- [ ] Watch tag cardinality (no raw user IDs or prompt text as tags)
- [ ] Provisioned Grafana dashboards (JSON in the repo)
- [ ] Structured JSON logging with a correlation ID per request (`x-request-id` returned in headers)
- [ ] Prometheus alert rules (error rate, p95 latency, circuit open)

**Done when:** `docker compose up` shows live dashboards with no manual Grafana setup.

### Day 11: Admin API for the dashboard
- [ ] Endpoints: summary KPIs, time series (requests, cost, latency p50/p95/p99), breakdown by model/key/prompt, recent requests (paginated), cache stats, provider health
- [ ] Admin auth (separate admin key or role)
- [ ] Pagination, filtering, and date-range parameters
- [ ] Query optimization: pre-aggregated hourly rollup table filled by a scheduled job (keeps the dashboard fast as logs grow)
- [ ] OpenAPI docs via springdoc

**Done when:** every dashboard number is available through a documented, fast API.

### Day 12: Frontend dashboard
- [ ] Vite + React + TS scaffold, API client layer, TanStack Query
- [ ] Overview page: KPI cards (requests, spend, p95, cache hit rate, error rate) + time-series charts
- [ ] Requests page: filterable, paginated table with a detail drawer
- [ ] Models and providers page: cost, latency, fallback frequency, circuit status
- [ ] Loading, empty, and error states on every view
- [ ] Responsive layout, dark/light theme

**Done when:** the overview and requests pages show real data from the running gateway.

### Day 13: Frontend management pages + stretch features
- [ ] API keys page: create, revoke, set limits and budgets, usage per key
- [ ] Prompts page: list, view versions, diff, promote/rollback, per-version stats
- [ ] Settings: routing/fallback chains and pricing table editor
- [ ] **Semantic cache:** embed prompts (embeddings API), store vectors in Redis or pgvector, hit when similarity ≥ threshold (configurable, off by default)
- [ ] **Request/response redaction:** option to mask PII or disable payload storage per key
- [ ] OpenTelemetry tracing export (if time allows)

**Done when:** everything is manageable from the UI without touching the DB or curl.

### Day 14: Hardening, load testing, docs, release
- [ ] k6 load test: throughput, p95 overhead added by the gateway (target under 20 ms excluding provider time), streaming under concurrency
- [ ] Chaos test: provider down, Redis down (gateway should degrade gracefully, not crash), Postgres slow
- [ ] Security pass: key hashing, no secrets in logs, input size limits, CORS, dependency scan
- [ ] Multi-stage Dockerfiles (backend + frontend), image size check
- [ ] Final CI: build, test, image build, optional push
- [ ] README: architecture diagram, quickstart (`docker compose up`), screenshots, benchmarks, design decisions
- [ ] ADRs in `docs/` for key choices (MVC vs WebFlux, Redis, micro-dollars, hashing keys)
- [ ] Optional deploy: a single VM or Fly.io/Render with a live demo link

**Done when:** a stranger can clone, run one command, and see a working gateway and dashboard.

---

## 6. Feature Coverage Checklist

| Required feature | Day |
|---|---|
| OpenAI-compatible proxy, multi-provider | 2-3 |
| Streaming | 3 |
| API keys and auth | 4 |
| Request logging | 4 |
| Cost tracking and budgets | 5, 7 |
| Caching (exact, then semantic) | 6, 13 |
| Rate limiting | 7 |
| Fallback, retry, circuit breaker | 8 |
| Prompt versioning | 9 |
| Metrics and dashboards | 10-13 |
| Docker, CI, load tests, docs | 1, 14 |

## 7. Daily Routine

1. Start: pull, run the stack, review the day's checklist.
2. Build the smallest vertical slice first, then refine.
3. Write tests alongside the code, not after.
4. End: run the full test suite, commit small focused commits with clear messages, tick the checklist.
5. If a day slips, move only stretch items (Day 13) and never core items.

## 8. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Streaming edge cases (disconnects, partial usage) | Build and test on Day 3, not at the end |
| Provider API costs during development | Use WireMock for tests; a cheap model for manual checks |
| Metrics tag explosion | Fixed low-cardinality tags only |
| Log table growth | Hourly rollups + retention job + indexes |
| Redis outage | Fail open on cache, fail safe on rate limit (configurable) |
| Scope creep | Stretch items are explicitly listed; everything else is out of scope |

## 9. Out of Scope (v1)

Multi-tenant billing/invoicing, user-facing login/SSO, fine-tuning management, non-chat endpoints (image/audio), and Kubernetes manifests (Compose is enough for v1).
