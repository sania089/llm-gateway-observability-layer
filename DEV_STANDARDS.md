# Development Standards

These rules apply to all backend, frontend, and DevOps work in this repo. The goal is code that is **small, clear, correct, and easy to scale**.

## 0. Core Principles

1. **Simplest thing that works well.** Pick the most direct solution. Do not add layers, patterns, or config "just in case."
2. **Readable over clever.** Code is read far more than written. A new teammate should follow any file in minutes.
3. **No speculative generality.** No interface with a single implementation unless it's a real extension point (e.g. `ProviderClient`). No abstract base classes without 2+ users.
4. **Small units.** Functions do one thing (about 20 lines or fewer). Classes have one reason to change. Files stay short.
5. **Use the framework.** Prefer Spring Boot, Micrometer, Resilience4j, and Bucket4j features over hand-rolled equivalents. Prefer the standard library over a new dependency.
6. **Stateless services.** All shared state lives in Redis or Postgres so instances can be added freely.
7. **Delete before you add.** Remove dead code, unused imports, and stale comments in the same change.
8. **Measure, don't guess.** Optimize only after a profile or load test shows the bottleneck, but never write obviously wasteful code (N+1 queries, unbounded lists, blocking the request path).

---

## 1. Project and Folder Structure

- **Package by feature**, not by layer. `cache/`, `ratelimit/`, `cost/` each hold their own controller, service, repository, and DTOs.
- Cross-cutting code goes in `common/` (errors, utils) and `config/`. Keep `common/` small; if something is used by one feature, it belongs to that feature.
- Dependencies point one way: `proxy` → `routing`/`cache`/`ratelimit`/`cost` → `provider`. No cycles. Features don't reach into each other's internals; they use public service methods.
- One top-level folder per concern: `backend/`, `frontend/`, `deploy/`, `load-tests/`, `docs/`.
- Names describe purpose: `CostCalculator`, `RateLimitService`, not `Helper`, `Manager`, `Util2`.
- Keep a `README.md` at root and a short one in each major folder if setup isn't obvious.

## 2. Backend (Java / Spring Boot)

### Code style
- Java 25 features where they simplify: `record` for DTOs and value objects, `sealed` types for closed hierarchies, pattern matching, `var` where the type is obvious, text blocks for SQL.
- Controllers are **thin**: parse, validate, delegate, return. No business logic.
- Services hold business logic. Repositories hold persistence only.
- Constructor injection only (no field `@Autowired`). Make dependencies `final`.
- Prefer immutability: `final` fields, immutable DTOs, unmodifiable collections.
- Return `Optional` from lookups instead of `null`. Never pass or return `null` collections; return empty ones.
- Use meaningful names; avoid abbreviations. Methods are verbs, booleans read as questions (`isExpired`).
- No magic numbers or strings. Use named constants or `@ConfigurationProperties`.
- Comments explain **why**, never what. Good names replace most comments.
- Use Lombok sparingly (or not at all); records already remove most boilerplate.

### Configuration
- All tunables (timeouts, limits, TTLs, pricing defaults, fallback chains) live in `application.yml` bound to typed `@ConfigurationProperties` records, validated with `@Validated`.
- Secrets come from environment variables only. Never commit `.env` with real values; commit `.env.example`.
- Profiles: `local`, `test`, `prod`. No logic keyed on profile names inside business code.

### API design
- REST, nouns for resources, plural, versioned (`/v1/...`, `/admin/v1/...`).
- Proxy endpoint stays **OpenAI-compatible** so existing SDKs work unchanged.
- Correct status codes (400 validation, 401 auth, 403 forbidden, 404, 409 conflict, 429 rate limit, 502/503/504 upstream).
- One consistent error body everywhere, produced by a single `@RestControllerAdvice`. Never leak stack traces or provider keys.
- Validate all input with Bean Validation (`@Valid`, `@NotBlank`, `@Size`). Enforce request body size limits.
- Paginate every list endpoint. No unbounded responses.
- Document with OpenAPI (springdoc).

### Concurrency and performance
- Use virtual threads (`spring.threads.virtual.enabled=true`) with simple blocking code instead of reactive code. Don't mix paradigms.
- **Never block the request path for logging or analytics.** Write logs through a bounded async queue with batched inserts.
- Set explicit **timeouts** on every outbound call (connect, read). No call without a timeout.
- Reuse HTTP clients and connection pools; never create a client per request.
- Bound every queue, cache, and buffer. Define behavior when full (drop, reject, or back-pressure).
- Avoid N+1 queries. Fetch what you need in one query; use projections instead of loading full entities for reports.
- Stream large or long responses; don't buffer them in memory.

### Resilience
- Retry only idempotent/retryable failures (429, 5xx, timeouts) with exponential backoff + jitter and a retry cap. Never retry 4xx.
- Circuit breakers per upstream provider. Fallbacks are explicit and observable (header + metric + log).
- Define failure behavior for each dependency: Redis down → cache fails open; rate limiter behavior is configurable; Postgres slow → logging queue absorbs it.
- Idempotent handlers where possible.

### Data and persistence
- Schema changes **only** through Flyway migrations. Never edit an applied migration; add a new one.
- Store money as integer micro-dollars (or `BigDecimal`), never `double`/`float`.
- Store timestamps in UTC (`timestamptz`, `Instant`).
- Add indexes for the actual query patterns (time ranges, foreign keys, filters). Check with `EXPLAIN` for dashboard queries.
- Pre-aggregate (rollup tables) for dashboards instead of scanning raw logs.
- Add retention policies for high-volume tables.
- Use parameterized queries only. Never concatenate SQL.

### Caching and rate limiting
- Cache keys are deterministic, namespaced, and versioned (`gw:v1:cache:{hash}`).
- Every cache entry has a TTL. No unbounded cache growth.
- Rate-limit state lives in Redis with atomic operations; never in app memory.

### Security
- Hash API keys (SHA-256 or better) before storing; show the raw key once at creation.
- Provider keys come from env/secret store and are never logged or returned.
- Never log prompts, completions, or secrets by default; make payload logging an explicit opt-in and support redaction.
- Constant-time comparison for secrets. Fail closed on auth errors.
- Lock down CORS to known origins. Admin endpoints require separate admin auth.
- Run dependency vulnerability scans in CI. Keep dependencies minimal and current.

### Observability
- Structured JSON logs with a correlation ID (`x-request-id`) on every line; return the ID to the client.
- Log levels: `ERROR` = needs action, `WARN` = degraded but handled, `INFO` = lifecycle events, `DEBUG` = off in prod.
- Micrometer metrics for every important path. **Low-cardinality tags only** (model, provider, status), never user IDs, prompts, or request IDs.
- Health, readiness, and liveness endpoints via Actuator.

## 3. Frontend (React + TypeScript)

- **TypeScript strict mode.** No `any`; type API responses once in `api/` and reuse them.
- Folder layout: `pages/` (route screens), `components/` (reusable UI), `api/` (typed client), `hooks/`, `lib/` (pure helpers). Co-locate a component's styles and tests with it.
- Function components and hooks only. Components are small and single-purpose (about 100 lines or fewer); extract when one grows beyond that.
- **Server state via TanStack Query** (caching, retries, refetching). Don't hand-roll fetch + `useEffect` + state. Keep local UI state local; add a global store only if truly needed.
- One API client module; no `fetch` calls scattered across components.
- Every data view handles **loading, empty, and error** states.
- Derive values during render instead of duplicating state. Memoize (`useMemo`, `React.memo`) only for measured problems.
- Paginate or virtualize large tables. Debounce search/filter inputs.
- Code-split routes (`React.lazy`). Keep the bundle lean; avoid heavy libraries for small jobs.
- Accessibility: semantic HTML, labels, keyboard navigation, sufficient contrast.
- Responsive layout and a consistent design tokens file (colors, spacing, typography). Support dark/light themes.
- Never put secrets in frontend code. Admin keys are entered at runtime, not bundled.
- Lint and format with ESLint + Prettier; no warnings in CI.

## 4. Testing

- **Test pyramid:** many fast unit tests, fewer integration tests, a few end-to-end tests.
- Unit-test pure logic thoroughly: cost math, cache-key generation, template rendering, routing decisions.
- Integration tests use **Testcontainers** (real Postgres/Redis) and **WireMock** (fake providers). Do not mock what you can run for real.
- Test failure paths: provider timeout, 429, 5xx, malformed response, Redis down, client disconnect mid-stream.
- Concurrency tests for rate limiter and budgets.
- Tests are deterministic, independent, and fast. No sleeps (use awaiting utilities), no shared mutable state, no real provider calls.
- Name tests by behavior: `returnsFallbackWhenPrimaryCircuitIsOpen`.
- Coverage is a guide, not a goal. Prioritize business-critical logic.
- Load-test with k6 before release; record the results in the README.

## 5. Git and Workflow

- `main` is always releasable. Work on short-lived branches (`feat/cache`, `fix/stream-disconnect`).
- **Conventional Commits** (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`). Small, focused commits, one logical change each.
- Pull requests are small and have a description of what changed and why, plus how it was tested.
- CI must pass (build, lint, tests) before merging.
- Never commit secrets, build output, IDE files, or large binaries.
- Record significant decisions as short ADRs in `docs/adr/`.

## 6. DevOps and Deployment

- Everything runs with a single `docker compose up`. No manual setup steps.
- **Multi-stage Docker builds**, slim base images, non-root user, pinned versions, `.dockerignore`.
- Config via environment variables (12-factor). Same image for every environment.
- Health checks in Compose and container definitions.
- Graceful shutdown enabled (`server.shutdown=graceful`) so in-flight streams finish.
- CI pipeline: build → lint → unit tests → integration tests → dependency scan → image build.
- Grafana dashboards and Prometheus rules are stored as code in `deploy/`.
- Resource limits set for containers; the app is stateless and horizontally scalable.

## 7. Documentation

- README covers: what it is, architecture diagram, quickstart, config reference, API examples, screenshots, benchmarks.
- Public APIs documented via OpenAPI; examples with curl.
- Docs describe decisions and usage, not what the code obviously does.
- Keep docs updated in the same PR as the code change.

## 8. Code Review Checklist

Before merging, confirm:

- [ ] Does it do only what's needed? Is there a simpler way?
- [ ] Are names clear, and are functions small and single-purpose?
- [ ] Is all input validated and all errors handled with the standard error shape?
- [ ] Do outbound calls have timeouts, and are queues/caches bounded?
- [ ] Is the request path free of blocking work that could be async?
- [ ] No N+1 queries; indexes exist for new query patterns?
- [ ] No secrets, prompts, or PII in logs?
- [ ] Metrics added with low-cardinality tags?
- [ ] Tests cover the happy path and the failure paths?
- [ ] Docs and config examples updated?
- [ ] No dead code, no unused dependencies, no commented-out code?

## 9. Anti-Patterns to Avoid

- Over-engineering: factories, builders, and strategy hierarchies for trivial problems.
- Premature optimization without measurements.
- Business logic in controllers or in the frontend.
- Shared mutable state in singletons; in-memory state that breaks horizontal scaling.
- Catching and swallowing exceptions; returning `null` to signal failure.
- Copy-pasted code (extract once it appears the third time, not the second).
- Storing floats for money; storing local time instead of UTC.
- High-cardinality metric labels.
- Giant PRs, giant classes, giant components.
