# Deployment Configuration Reference

Reference for all configurable properties used by the `itrade-load-creation-agent`
when deploying via Docker (e.g. on GCP Cloud Run / GKE).

Every property below can be overridden with an environment variable using Spring
Boot **relaxed binding**: uppercase the name and replace `.` and `-` with `_`.
Examples:

| Property | Environment variable |
|---|---|
| `load.api.x-api-key` | `LOAD_API_X_API_KEY` |
| `agent.omsr.mongo.uri` | `AGENT_OMSR_MONGO_URI` |
| `agent.browser.password` | `AGENT_BROWSER_PASSWORD` |

> ⚠️ **Secrets** (marked 🔒) must be supplied from **GCP Secret Manager** /
> injected env vars — never baked into the image or committed.

---

## 1. Secrets (GCP Secret Manager)

| Property | Repo default | Notes |
|---|---|---|
| 🔒 `load.api.x-api-key` | `4206d6c3-…` (hardcoded) | API key for the load creation API. Move out of source. |
| 🔒 `agent.browser.username` | `lls.superadmin.user1@hwyhaul.com` | HwyHaul ops portal login. |
| 🔒 `agent.browser.password` | `password` (hardcoded) | HwyHaul ops portal password. |
| 🔒 `agent.omsr.username` | `${OMSR_USERNAME:}` | OMSR (iTradeNetwork) login. Already env-driven. |
| 🔒 `agent.omsr.password` | `${OMSR_PASSWORD:}` | OMSR password. Already env-driven. |
| 🔒 `openai.api-key` | *(not set)* | Required only when `llm.enabled=true`. |

---

## 2. MongoDB

| Property | Repo default | Notes |
|---|---|---|
| `agent.omsr.mongo.enabled` | `true` | Master switch for Mongo integration. |
| 🔒 `agent.omsr.mongo.uri` | `mongodb://127.0.0.1:27017/ai_agent_config?replicaSet=rs0` | **Localhost — must be repointed** to deployed Mongo (Atlas/GCP). Use `mongodb+srv://…` with creds from Secret Manager. |
| `agent.omsr.mongo.database` | `ai_agent_config` | |
| `agent.omsr.mongo.collection` | `3p_rpa_config` | Config collection. |
| `agent.omsr.mongo.agent-id` | `omsr` | |
| `agent.omsr.mongo.process-existing-on-startup` | `false` | |
| `agent.omsr.mongo.mark-dispatched` | `true` | |
| `agent.omsr.mongo.status-field` | `eventStatus` | |
| `agent.omsr.mongo.event-store-enabled` | `true` | |
| `agent.omsr.mongo.event-store-collection` | `3p_rpa_event_log` | |
| `agent.omsr.mongo.processed-load-store-enabled` | `true` | |
| `agent.omsr.mongo.processed-load-collection` | `3p_rpa_extracted_loads` | |

---

## 3. Environment URLs

| Property | Repo default (QA) | Notes |
|---|---|---|
| `load.api.create-url` | `https://qa.sentinel.hwyhaul.com/core/hwyhaul/services/loads` | Point to target env. |
| `load.api.enabled` | `true` | Set `false` to disable real API calls. |
| `services.address.lookup.base-url` | *(empty)* | Address lookup service base URL. |
| `agent.browser.auth-url` | `https://qa.ops.hwyhaul.com/auth` | |
| `agent.browser.orders-url` | `https://qa.ops.hwyhaul.com/orders?...` | |
| `agent.omsr.auth-url` | `https://omsr.itradenetwork.com/loginv2` | 3rd-party (prod). |
| `agent.omsr.orders-url` | `https://omsr.itradenetwork.com/app/enterprise?...` | |
| `agent.omsr.logout-url` | `https://omsr.itradenetwork.com/logout` | |

---

## 4. Container / Runtime

| Property | Repo default | Recommended for GCP | Notes |
|---|---|---|---|
| `server.port` | `8080` (Spring default) | honor Cloud Run `PORT` (`8080`) | |
| `agent.omsr.headless` | `true` | `true` | No display in container. |
| `agent.omsr.debug-directory` | `target/playwright-debug/omsr` | `/tmp/playwright-debug/omsr` | Use a writable path. |
| `mcp.jar-path` | `../java-mcp-server/target/java-mcp-server-0.0.1-SNAPSHOT-exec.jar` | absolute in-image path, e.g. `/app/java-mcp-server.jar` | MCP jar launched as subprocess. |
| `llm.enabled` | `false` | as needed | If `true`, requires `openai.api-key`. |
| `logging.level.com.hwyhaul.agent` | `DEBUG` | `INFO` | Reduce log volume in prod. |

### Container gotchas (not properties)
- **Playwright/Chromium**: the OMSR + browser scraping require a headless
  browser. Base the image on `mcr.microsoft.com/playwright/java` or run
  `playwright install --with-deps`.
- **MCP subprocess**: `McpProcessConfig` spawns the `java-mcp-server` jar and
  passes `MCP_AUTH_URL`, `MCP_ORDERS_URL`, `MCP_USERNAME`, `MCP_PASSWORD`,
  `MCP_ORDER_ROW_SELECTOR` (derived from `agent.browser.*`). **Both jars must be
  in the same image** and `java` must be on `PATH`.

---

## 5. Scheduled Job (OMSR polling)

| Property | Repo default | Notes |
|---|---|---|
| `agent.omsr.job.enabled` | `false` | Set `true` to enable scheduled scraping in deployed env. |
| `agent.omsr.job.initial-delay-ms` | `30000` | |
| `agent.omsr.job.fixed-delay-ms` | `300000` | Poll interval (5 min). |
| `agent.omsr.run-timeout-ms` | `600000` | Per-run timeout (10 min). |
| `agent.omsr.max-loads-from-first-screen` | `0` | `0` = scrape all pages. |
| `agent.omsr.events.core-pool-size` | `1` | Event executor pool. |
| `agent.omsr.events.max-pool-size` | `2` | |
| `agent.omsr.events.queue-capacity` | `25` | |

---

## 6. Selectors (rarely changed; only if the scraped UI changes)

| Property | Repo default |
|---|---|
| `agent.browser.order-row-selector` | `[data-testid='orders-table-row'], …` |
| `agent.omsr.user-name-selector` | `input[name='userName']` |
| `agent.omsr.password-selector` | `input[name='password']` |
| `agent.omsr.submit-selector` | `button[type='submit']` |
| `agent.omsr.logout-enabled` | `true` |
| `agent.omsr.logout-selector` | `a[href*='/logout' i], …` |
| `agent.omsr.logout-menu-selector` | `itn-user-headshot .mat-mdc-menu-trigger, …` |
| `agent.omsr.load-row-selector` | `tbody tr, [role='row']` |
| `agent.omsr.load-link-selector` | `a, button, [role='link']` |

---

## 7. Load Mapping Defaults

These define how scraped orders map to load API payloads. Override per
customer/agent/environment as needed. Most are stable business defaults.

### Load attributes
| Property | Default |
|---|---|
| `agent.load.size` | `TL` |
| `agent.load.driver-combination` | `SOLO` |
| `agent.load.packaging` | `Pallet` |
| `agent.load.equipment` | `REEFER` |
| `agent.load.reefer-mode` | `1` |
| `agent.load.temperature-source` | `BOL` |
| `agent.load.pricing-type` | `SPOT` |
| `agent.load.payment-addon-type-id` | `96bf6c05-4015-11ef-b506-42010af72332` |
| `agent.load.payment-addon-type-external-id` | `LineHa` |
| `agent.load.unit-price` | `5000` |
| `agent.load.unit-count` | `1` |
| `agent.load.default-weight` | `42000` |
| `agent.load.default-pallet-count` | `22` |
| `agent.load.default-case-count` | `2010` |
| `agent.load.default-pickup-number` | `P001` |
| `agent.load.default-dropoff-number` | `Need to Schedule` |
| `agent.load.default-pickup-po-number` | `P001` |
| `agent.load.currency.name` | `USD` |
| `agent.load.currency.symbol` | `US$` |

### IDs (environment-specific — verify per target env)
| Property | Default |
|---|---|
| `agent.load.credit-recipient.user-id` | `90ddef76-5889-11ef-b506-42010af72332` |
| `agent.load.credit-recipient.commission-plan-id` | `d2aef315-5889-11ef-b506-42010af72332` |
| `agent.load.company.id` | `b3b74b30-5cfc-4301-bfe3-c7c5655c658b` |
| `agent.load.shipper-id` | `7775aa16-293d-4808-80d3-16165df601e8` |
| `agent.load.commodity-id` | `95288fd1-3dba-11ef-b506-42010af72332` |
| `agent.load.commodities[0..3].{id,group,name}` | Produce: Bell Peppers, Tomatoes, Mini Peppers, Cucumbers |
| `agent.load.address.id.pickup` | `b6a663d3-569c-4a14-bd87-eb3973029112` |
| `agent.load.address.id.dropoff` | `48d069cf-233d-43c1-a5dd-79b60e5fe25e` |

### Schedule
| Property | Default |
|---|---|
| `agent.load.schedule.default-timezone` | `America/Chicago` |
| `agent.load.schedule.default-pickup-days-from-now` | `0` |
| `agent.load.schedule.default-dropoff-days-after-pickup` | `1` |
| `agent.load.schedule.default-pickup-date-time` | `2026-05-15T08:00:00-05:00[America/Chicago]` |
| `agent.load.schedule.default-dropoff-date-time` | `2026-05-16T08:00:00-05:00[America/Chicago]` |

### Address defaults
`agent.load.address.defaults.pickup.*` and `agent.load.address.defaults.dropoff.*`
hold full default pickup/dropoff facility details (name, address lines, city,
state, zip, country, lat/lon, timezone, etc.). See `application.properties` for
the complete list.

### Supplement data
| Property | Default |
|---|---|
| `agent.load.supplement-data.load-management-team-id` | `ff123497-55d2-11ef-b506-42010af72332` |
| `agent.load.supplement-data.source-system` | `OMSR` |
| `agent.load.supplement-data.source-type` | `OMSR_LOAD` |
| `agent.load.supplement-data.default-source-order-id` | `UNKNOWN` |
| `agent.load.supplement-data.default-customer-name` | `UNKNOWN` |
| `agent.load.supplement-data.default-page-url` | `UNKNOWN` |

---

## 8. HTTP timeouts

| Property | Default |
|---|---|
| `load.api.connect-timeout-ms` | `10000` |
| `load.api.read-timeout-ms` | `60000` |

---

## Minimal env var set for a GCP deployment

```bash
# Secrets (from Secret Manager)
LOAD_API_X_API_KEY=...
AGENT_BROWSER_USERNAME=...
AGENT_BROWSER_PASSWORD=...
OMSR_USERNAME=...
OMSR_PASSWORD=...
# OPENAI_API_KEY=...           # only if LLM_ENABLED=true

# MongoDB
AGENT_OMSR_MONGO_URI=mongodb+srv://<user>:<pass>@<cluster>/ai_agent_config?retryWrites=true&w=majority

# Environment URLs (point to target env)
LOAD_API_CREATE_URL=https://<env>.sentinel.hwyhaul.com/core/hwyhaul/services/loads
AGENT_BROWSER_AUTH_URL=https://<env>.ops.hwyhaul.com/auth
AGENT_BROWSER_ORDERS_URL=https://<env>.ops.hwyhaul.com/orders?...

# Runtime
SERVER_PORT=8080
AGENT_OMSR_HEADLESS=true
AGENT_OMSR_DEBUG_DIRECTORY=/tmp/playwright-debug/omsr
MCP_JAR_PATH=/app/java-mcp-server.jar
AGENT_OMSR_JOB_ENABLED=true
LOAD_API_ENABLED=true
LLM_ENABLED=false
LOGGING_LEVEL_COM_HWYHAUL_AGENT=INFO
```
