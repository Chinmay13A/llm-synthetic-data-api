# LLM Synthetic Data API

Spring Boot REST API that generates realistic, schema-compliant test data from JSON Schema using LLMs, with runtime switching across OpenAI, Claude, Groq and Grok.

## Tech Stack

- **Java 17**
- **Spring Boot 4.1** (Web, Validation)
- **Maven**
- **Lombok**
- **LLM providers** via HTTP (`RestClient`) — OpenAI, Claude (Anthropic), Grok (xAI), Groq (free-tier, recommended for local testing)
- **Bucket4j + Redis (Lettuce)** — distributed per-IP and global rate limiting on `POST /generate`

## Project Structure

```
src/main/java/com/dummybackend/generatorservice/
├── GeneratorserviceApplication.java
├── config/
│   └── AppConfig.java                 # Enables configuration properties
├── controller/
│   ├── GenerateController.java        # POST /generate
│   └── HealthController.java          # GET /health
├── dto/
│   ├── GenerateRequest.java           # provider + count + schema
│   └── GenerateResponse.java          # count + data
├── llm/
│   ├── LlmProvider.java               # Provider strategy interface
│   ├── LlmProviderRegistry.java       # Resolves provider per request
│   ├── LlmProvidersProperties.java    # Per-provider config
│   ├── OpenAiLlmProvider.java
│   ├── ClaudeLlmProvider.java
│   ├── GrokLlmProvider.java           # xAI Grok
│   ├── GroqLlmProvider.java           # Groq (free tier)
│   └── ChatCompletionsSupport.java    # Shared OpenAI-compatible helpers
├── ratelimit/
│   ├── RateLimitProperties.java       # Limits + Redis connection settings
│   ├── RateLimitService.java          # Per-IP and global Bucket4j buckets
│   ├── RateLimitDecision.java         # Allowed / rejected (client or global) + retry-after
│   ├── BucketStore.java               # Where buckets live
│   ├── RedisBucketStore.java          # Redis-backed store (lazy connect, auto-expiring keys)
│   ├── RateLimiterUnavailableException.java
│   └── RateLimitFilter.java           # OncePerRequestFilter on /generate
├── service/
│   ├── GeneratorService.java          # Orchestrates prompt + LLM + parse
│   ├── PromptBuilder.java
│   └── SchemaValidator.java
└── exception/
    ├── GlobalExceptionHandler.java
    ├── SchemaValidationException.java
    ├── UnknownLlmProviderException.java
    ├── LlmGenerationException.java
    └── ErrorResponse.java
```

## Prerequisites

- JDK 17+
- Maven 3.9+ (or use the included Maven Wrapper)
- Redis 6+ on `localhost:6379` (or set `REDIS_URL`). Required for `POST /generate` while rate limiting is enabled.
- API key(s) for the provider(s) you use:
  - `GROQ_API_KEY` (default provider; free tier, good for local testing)
  - `OPENAI_API_KEY`
  - `ANTHROPIC_API_KEY` (Claude)
  - `GROK_API_KEY` (xAI Grok — not the same as Groq)

Unused providers do not need keys; the key is checked only when that provider is selected.

## Secrets (`.env`)

API keys are read from environment variables (see `application.properties`). Locally, put them in a project-root **`.env`** file — Spring Boot loads it via `springboot4-dotenv` (real OS env vars still take precedence).

1. Copy `.env.example` → `.env`
2. Fill in the keys you need (at least `GROQ_API_KEY` for the default provider)
3. Keep `.env` out of git (already in `.gitignore`)

## Configuration

Set in `src/main/resources/application.properties`, `.env`, or OS environment variables:

| Property | Description | Default |
|---|---|---|
| `server.port` | HTTP port | `8000` |
| `rate-limit.enabled` | Enable per-IP rate limiting on `POST /generate` | `true` |
| `rate-limit.requests-per-minute` | Max requests per IP per minute | `5` |
| `rate-limit.requests-per-day` | Max requests per IP per day | `20` |
| `rate-limit.global-burst` | Max requests at once across all clients; must not exceed the daily limit | `20` |
| `rate-limit.global-requests-per-day` | Global refill rate, in requests per day across all clients | `200` |
| `rate-limit.redis-url` | Redis connection URL (env `REDIS_URL`) | `redis://localhost:6379` |
| `rate-limit.redis-timeout` | Connect and command timeout for Redis | `2s` |
| `llm.default-provider` | Used when request omits `provider` | `groq` |
| `llm.openai.*` | `base-url`, `api-key`, `model`, `max-tokens` | see `application.properties` |
| `llm.claude.*` | same | … |
| `llm.grok.*` | same (xAI) | … |
| `llm.groq.*` | same (Groq free tier) | … |

API keys are env-backed (from `.env` or the real environment):

```properties
llm.groq.api-key=${GROQ_API_KEY:}
llm.openai.api-key=${OPENAI_API_KEY:}
llm.claude.api-key=${ANTHROPIC_API_KEY:}
llm.grok.api-key=${GROK_API_KEY:}
```

**Note:** `groq` (Groq) and `grok` (xAI) are different providers.

## Redis

Rate limits are stored in Redis, so they are shared across instances and survive restarts.

Redis does not run natively on Windows. Options:

- **WSL (Ubuntu):** `sudo apt install redis-server`, then `sudo service redis-server start`. Reachable from Windows at `localhost:6379`.
- **Memurai:** Windows-native, Redis-compatible server.
- **Docker:** `docker run -p 6379:6379 redis:7`

Check it is running with `redis-cli ping` (expects `PONG`).

## Run

```bash
./mvnw spring-boot:run
```

On Windows:

```bash
.\mvnw.cmd spring-boot:run
```

The server starts on **http://localhost:8000**.

## API

### Health check

```http
GET /health
```

Response: `Ok`

### Generate dummy data

```http
POST /generate
Content-Type: application/json
```

**Request body**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `provider` | string | no (defaults to `groq`) | `openai`, `claude`, `grok`, or `groq` |
| `count` | integer | no (defaults to `1`) | 1–50; response always contains exactly this many records |
| `schema` | object | yes | 1–30 fields |

**Exact count:** If the LLM returns too many records, extras are truncated. If it returns too few, the service retries once with a corrective prompt; if still short, the API responds with **502**.

**Example**

```json
{
  "provider": "groq",
  "count": 3,
  "schema": {
    "name": "string",
    "email": "string",
    "age": {
      "type": "integer",
      "min": 18,
      "max": 65
    },
    "status": {
      "type": "enum",
      "values": ["active", "inactive", "pending"]
    },
    "dob": "date",
    "verified": "boolean",
    "score": {
      "type": "float",
      "min": 0,
      "max": 100
    }
  }
}
```

**Response**

```json
{
  "count": 3,
  "data": [
    {
      "name": "Aisha Khan",
      "email": "aisha.khan@example.com",
      "age": 29,
      "status": "active",
      "dob": "1996-03-14",
      "verified": true,
      "score": 87.5
    }
  ]
}
```

## Rate limiting

`POST /generate` is limited with Bucket4j token buckets stored in Redis. `/health` is not limited. Two limits are checked in order:

| Limit | Redis key | Default | Purpose |
|---|---|---|---|
| Per client | `rl:ip:{ip}` | 5 / minute and 20 / day | Fairness: no single IP can use up the shared quota |
| Global | `rl:global` | Burst of 20, refilling 200 / day | Caps total LLM spend, however many IPs are used |

A token bucket allows its full capacity at once, plus whatever refills afterwards. So the global bucket holds only `global-burst` tokens, and any 24 hours allows at most `global-burst + global-requests-per-day` requests (220 with the defaults). After a burst, roughly one request is allowed every 7 minutes until the bucket refills.

- Client IP is the connection's remote address. A client-supplied `X-Forwarded-For` is ignored, so it cannot be used to reset the limit. If you deploy behind a reverse proxy, set `server.forward-headers-strategy=native` and configure the trusted proxy instead.
- The per-client limit is checked first, so a client that is over its own limit does not use up the global quota.
- Buckets refill gradually rather than resetting at fixed times, so there is no burst at the window boundary.
- Keys expire on their own once a bucket would be full again.
- Changing a limit only affects buckets created afterwards. To apply it immediately, delete the existing keys: `redis-cli --scan --pattern 'rl:*' | xargs redis-cli del`.

When a limit is exceeded:

- **Status:** `429 Too Many Requests`
- **Header:** `Retry-After: <seconds until a token is available>`
- **Body:**

```json
{
  "message": "Rate limit exceeded",
  "details": ["Too many requests. Limits are 5 requests per minute and 20 requests per day per IP."]
}
```

When the global limit is hit, the detail is `"The service has reached its overall request limit. Try again later."`

**Fail-closed:** if Redis is unreachable, `POST /generate` returns `503 Service Unavailable` instead of calling the LLM without limits. The app still starts without Redis and reconnects automatically once Redis is back.

```json
{
  "message": "Rate limiter unavailable",
  "details": ["Requests are temporarily rejected because the rate limiter cannot be reached."]
}
```

Set `rate-limit.enabled=false` to disable rate limiting locally (Redis is then not needed).

## Schema Format

Each field in `schema` can be a shorthand type string or an object:

- Shorthand: `"age": "integer"` → `{ "type": "integer" }`
- Full: `"age": { "type": "integer", "min": 18, "max": 65 }`

**Allowed types:** `string`, `integer`, `float`, `boolean`, `date`, `enum`, `object`, `array`

| Constraint | Applies to | Notes |
|---|---|---|
| `type` | all | required |
| `min` / `max` | `integer`, `float` | `min` must not exceed `max` |
| `values` | `enum` | required non-empty array |
| `fields` | `object` | required non-empty map of nested field definitions |
| `items` | `array` | required; type string or full field definition (including nested `object`) |
| `description` | any | optional hint for the LLM |

- Max **30** fields **per object level**
- Max nesting depth **3** (root schema is depth 0)

**Nested example**

```json
{
  "name": "string",
  "address": {
    "type": "object",
    "fields": {
      "city": "string",
      "zip": "integer"
    }
  },
  "tags": {
    "type": "array",
    "items": "string"
  },
  "orders": {
    "type": "array",
    "items": {
      "type": "object",
      "fields": {
        "id": "integer",
        "total": "float"
      }
    }
  }
}
```

## Error Responses

All errors use:

```json
{
  "message": "...",
  "details": ["..."]
}
```

| Status | When |
|---|---|
| `400 Bad Request` | Invalid request body, schema, or unknown `provider` |
| `429 Too Many Requests` | Per-IP or global rate limit exceeded on `POST /generate` |
| `502 Bad Gateway` | LLM call or parse failure |
| `503 Service Unavailable` | Rate limiter (Redis) unreachable; request rejected without calling the LLM |

## Adding a new provider

1. Add a `@Component` implementing `LlmProvider` (`id()` + `complete(prompt)`).
2. Add its config slice under `llm.<id>.*` in `application.properties`.
3. No changes needed to `GeneratorService` or `LlmProviderRegistry`.
