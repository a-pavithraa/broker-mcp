# Broker MCP Server

MCP (Model Context Protocol) server for ICICI Direct (Breeze) and Zerodha trading accounts.
Gives Claude Desktop and Codex access to your portfolio, quotes, and trading tools.

Supports:
- `stdio` MCP for local desktop clients (JAR or Docker)
- HTTP (Streamable) MCP via Spring profile `http` (Docker or local JAR)
- Persistent Zerodha tradebook imports for accurate FIFO tax reporting
- Persistent corporate-action registry for splits, bonuses, and IPO allotment hints

---

## Quick Start

> **No Python / automation?** Skip to [Manual Session Setup](#manual-session-setup-no-automation).

### 1. Install Python dependencies (once)

```bash
pip install playwright breeze-connect python-dotenv
playwright install chromium
```

### 2. Create your `.env` file (once)

```bash
cp .env.example .env
# Edit .env — only API key + secret are required per broker.
# User ID and password are optional; the login script prompts if missing.
```

### 3. Build the JAR (once)

```bash
./mvnw clean package -DskipTests
```

### 4. Configure your AI agent (once)

See [Deployment Modes](#deployment-modes) below for the config block matching your setup.

### 5. Refresh sessions (every morning)

```bash
./refresh_trading_sessions.sh
```

The script logs you in, writes fresh tokens to `~/.broker-mcp/.env.session`, updates your Claude Desktop config, and restarts Claude Desktop.

---

## Directory Structure

All runtime data lives under `~/.broker-mcp/`:

This directory also holds `stock-corporate-actions.json`, the persisted reviewed corporate-action registry used by tax harvest.

```
~/.broker-mcp/
├── .env.session                  # Auto-generated session tokens (shared by all brokers)
├── logs/
│   ├── server.log                # MCP server log
│   └── trade-executions.log      # Order execution audit trail
├── imports/                      # Drop Zerodha Console tradebook CSVs here
├── zerodha-tradebook.json        # Persisted tradebook data
└── playwright/
    └── zerodha/                  # Shared browser state (optional)
```

---

## Environment Variables

There are two files involved — you only edit the first one:

| File | What goes here | Who writes it |
|------|---------------|---------------|
| `.env` (project root) | API keys + secrets, optional login credentials | You (once) |
| `~/.broker-mcp/.env.session` | Session tokens, enabled flags, API keys | Refresh script (daily) |

### `.env` — your credentials

Only API key + secret are required per broker. Everything else is optional.

```env
# ICICI Direct (skip if you only use Zerodha)
BREEZE_API_KEY=your_api_key
BREEZE_SECRET=your_api_secret

# Zerodha (skip if you only use Breeze)
ZERODHA_API_KEY=your_api_key
ZERODHA_API_SECRET=your_api_secret
```

Optional credentials (prompted interactively if missing):
`ICICI_USER_ID`, `ICICI_PASSWORD`, `ZERODHA_USER_ID`, `ZERODHA_PASSWORD`

Optional automation helpers:
`GMAIL_USER` + `GMAIL_APP_PASSWORD` (auto-read Breeze OTP),
`ZERODHA_TOTP_SECRET` (auto-submit 2FA)

See `.env.example` for the full list with descriptions.

### `~/.broker-mcp/.env.session` — auto-generated

The refresh script writes this file daily. It contains everything the server needs:
`BREEZE_ENABLED`, `BREEZE_API_KEY`, `BREEZE_SECRET`, `BREEZE_SESSION`,
`ZERODHA_ENABLED`, `ZERODHA_API_KEY`, `ZERODHA_USER_ID`, `ZERODHA_ACCESS_TOKEN`.

Only brokers you logged into get `*_ENABLED=true` — the other broker's beans won't load.

### Optional server tuning

These rarely need changing. Set them in your MCP `env` block or shell environment.

| Variable | Default | Description |
|----------|---------|-------------|
| `BROKER_TOOLS_MODE` | `readonly` | `readonly` or `full` (enables order placement + tradebook import) |
| `BROKER_CORPORATE_ACTIONS_STORE_PATH` | `~/.broker-mcp/stock-corporate-actions.json` | External writable corporate-action registry used by tax harvesting |
| `BREEZE_TRADING_ENABLED` | `false` | Enable live order execution |
| `BREEZE_MAX_ORDER_VALUE` | `500000` | Max single order value in INR |
| `BROKER_PREFERRED_DATA_BROKER` | `zerodha` | Preferred broker for market data |
| `BROKER_DEFAULT_ORDER_BROKER` | `zerodha` | Default broker for order placement |

---

## Deployment Modes

Run `./refresh_trading_sessions.sh show-config` to print ready-to-use config blocks with your current credentials.

Add to your Claude Desktop config:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

### Mode 1: stdio (JAR) — simplest, no Docker needed

Claude Desktop launches the JAR directly. Credentials live in the `env` block.

```json
{
  "mcpServers": {
    "trading": {
      "command": "java",
      "args": ["-jar", "/ABSOLUTE/PATH/TO/target/broker-mcp-0.0.1-SNAPSHOT.jar"],
      "env": {
        "BREEZE_ENABLED":       "true",
        "BREEZE_API_KEY":       "your_icici_api_key",
        "BREEZE_SECRET":        "your_icici_api_secret",
        "BREEZE_SESSION":       "",
        "ZERODHA_ENABLED":      "true",
        "ZERODHA_API_KEY":      "your_zerodha_api_key",
        "ZERODHA_ACCESS_TOKEN": "",
        "ZERODHA_USER_ID":      "your_zerodha_user_id"
      }
    }
  }
}
```

The refresh script fills in session tokens, sets `*_ENABLED`, and restarts Claude Desktop.

> **Only use one broker?** Remove the other broker's keys and set its `*_ENABLED` to `false`.

### Mode 2: stdio (Docker)

Claude Desktop launches the container and pipes stdio. The Dockerfile defaults to HTTP (streamable), so override with `-e SPRING_PROFILES_ACTIVE=`.

```json
{
  "mcpServers": {
    "trading": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/ABSOLUTE/HOST/PATH/.broker-mcp:/data",
        "-e", "SPRING_PROFILES_ACTIVE=",
        "-e", "BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json",
        "-e", "BREEZE_ENABLED=true",
        "-e", "BREEZE_API_KEY=your_icici_api_key",
        "-e", "BREEZE_SECRET=your_icici_api_secret",
        "-e", "BREEZE_SESSION=",
        "-e", "ZERODHA_ENABLED=true",
        "-e", "ZERODHA_API_KEY=your_zerodha_api_key",
        "-e", "ZERODHA_ACCESS_TOKEN=",
        "-e", "ZERODHA_USER_ID=your_zerodha_user_id",
        "broker-mcp"
      ]
    }
  }
}
```

The refresh script fills in session tokens, sets `*_ENABLED`, and restarts Claude Desktop.

### Mode 3: HTTP Streamable (Docker, persistent container)

You run the container yourself; Claude connects via URL. Set once in Claude Desktop config:

```json
{
  "mcpServers": {
    "trading": { "url": "http://localhost:8080/mcp" }
  }
}
```

Start/restart after each token refresh:

```bash
docker stop broker-mcp 2>/dev/null; docker rm broker-mcp 2>/dev/null
docker run -d --name broker-mcp -p 8080:8080 \
  -v "$HOME/.broker-mcp:/data" \
  -e BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json \
  -e BREEZE_ENABLED=true \
  -e BREEZE_API_KEY=your_icici_api_key \
  -e BREEZE_SECRET=your_icici_api_secret \
  -e BREEZE_SESSION=your_session_token \
  -e ZERODHA_ENABLED=true \
  -e ZERODHA_API_KEY=your_zerodha_api_key \
  -e ZERODHA_ACCESS_TOKEN=your_access_token \
  -e ZERODHA_USER_ID=your_zerodha_user_id \
  broker-mcp
```

No Claude restart needed — the HTTP URL stays the same.

For tradebook imports and persisted corporate-action edits, mount volumes:

```bash
docker run -d --name broker-mcp -p 8080:8080 \
  -v "$HOME/.broker-mcp/imports:/imports:ro" \
  -v "$HOME/.broker-mcp:/data" \
  -e BROKER_TOOLS_MODE=full \
  -e BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json \
  # ... credential flags ...
  broker-mcp
```

### Mode 4: HTTP Streamable (local JAR / IDE)

Same Claude config as Mode 3. The launcher scripts load `~/.broker-mcp/.env.session` automatically:

```bash
# Bash
./run-with-env.sh http

# PowerShell
./run-with-env.ps1 -Profile http
```

Or manually:

```bash
SPRING_PROFILES_ACTIVE=http \
BREEZE_ENABLED=true \
BREEZE_API_KEY=your_icici_api_key \
BREEZE_SESSION=your_session_token \
ZERODHA_ENABLED=true \
ZERODHA_API_KEY=your_zerodha_api_key \
ZERODHA_ACCESS_TOKEN=your_access_token \
ZERODHA_USER_ID=your_zerodha_user_id \
java -jar target/broker-mcp-0.0.1-SNAPSHOT.jar
```

Or set `SPRING_PROFILES_ACTIVE=http` plus credentials in your IDE run configuration.

### Mode detection

| `mcpServers` entry shape | Mode |
|--------------------------|------|
| `command: "java"` with `env: {…}` | stdio JAR |
| `command: "docker"` with `-e` flags in `args` | stdio Docker |
| `url: "http://…"` | HTTP Streamable (Docker or local) |

---

## Daily Workflow

Sessions expire daily (Zerodha at 06:00 IST, Breeze at midnight).

```bash
./refresh_trading_sessions.sh            # auto-detect configured brokers
./refresh_trading_sessions.sh breeze     # ICICI Direct only
./refresh_trading_sessions.sh zerodha    # Zerodha only
./refresh_trading_sessions.sh both       # both, sequentially
./refresh_trading_sessions.sh show-config # print configs for all four modes
```

The script:
1. Detects which brokers are configured (only API key + secret needed)
2. Opens a browser and logs you in (user ID / password prompted if not in `.env`)
3. **Breeze**: reads OTP from Gmail automatically, or prompts for manual entry
4. **Zerodha**: submits TOTP automatically, or keeps the browser open for manual 2FA
5. Writes fresh session tokens to `~/.broker-mcp/.env.session`
6. Updates your Claude Desktop config and restarts it

---

## Broker Setup

### ICICI Direct (Breeze)

1. Register at [ICICI Direct Developer Portal](https://api.icicidirect.com/)
2. Create an app — note the **API Key** and **Secret**
3. Set the redirect URL to `http://127.0.0.1`
4. Add to `.env`:

```env
BREEZE_API_KEY=...
BREEZE_SECRET=...
```

Optionally add `ICICI_USER_ID` and `ICICI_PASSWORD` — if omitted, the script prompts at runtime.

**OTP options:**

| Option | What happens |
|--------|-------------|
| No Gmail config (default) | Script pauses and prompts you to type the OTP |
| Gmail configured | OTP is read from Gmail automatically |

To enable automatic OTP:
1. Enable Google 2-Step Verification: https://myaccount.google.com/security
2. Generate an App Password: https://myaccount.google.com/apppasswords
3. Enable Gmail IMAP: Gmail Settings → Forwarding and POP/IMAP
4. Add to `.env`:

```env
GMAIL_USER=your.email@gmail.com
GMAIL_APP_PASSWORD=abcdefghijklmnop
```

### Zerodha (Kite Connect)

1. Register at [Kite Connect Developer](https://kite.trade/)
2. Create an app — note the **API Key** and **Secret**
3. Add to `.env`:

```env
ZERODHA_API_KEY=...
ZERODHA_API_SECRET=...
```

Optionally add `ZERODHA_USER_ID` and `ZERODHA_PASSWORD`.

**2FA options:**

| Option | What happens |
|--------|-------------|
| No TOTP secret (default) | Browser opens, you enter the 6-digit code manually |
| TOTP secret configured | 2FA is submitted automatically |

```env
ZERODHA_TOTP_SECRET=JBSWY3DPEHPK3PXP   # base32 seed from authenticator app
```

---

## Manual Session Setup (No Automation)

If you prefer not to use the Python scripts, get session tokens manually through a browser.

### Breeze (ICICI Direct)

1. Open in your browser (replace with your API key):
   ```
   https://api.icicidirect.com/apiuser/login?api_key=YOUR_BREEZE_API_KEY
   ```
2. Log in and complete OTP
3. The browser redirects to:
   ```
   http://127.0.0.1/?apisession=XXXXXXXXXXXXXXXX
   ```
4. Copy the value after `apisession=` — that is your `BREEZE_SESSION`
5. Add it to your MCP config (see [Deployment Modes](#deployment-modes)) and restart Claude Desktop

### Zerodha

1. Open in your browser (replace with your API key):
   ```
   https://kite.zerodha.com/connect/login?v=3&api_key=YOUR_ZERODHA_API_KEY
   ```
2. Log in and complete 2FA
3. The browser redirects to:
   ```
   https://your-redirect-url/?request_token=XXXXXXXX&action=login&status=success
   ```
4. Copy the `request_token`, then exchange it for an access token using the [Kite Connect session API](https://kite.trade/docs/connect/v3/user/#token-exchange)
5. Add the `access_token` as `ZERODHA_ACCESS_TOKEN` in your MCP config and restart Claude Desktop

> The Zerodha token exchange requires an API call with a checksum. Setting up the Python automation (5 minutes) is easier long-term since sessions expire daily.

---

## Stock Universe

`stock-universe.csv` (bundled in the JAR) provides sector, industry, and corporate-group metadata used by portfolio and tax-harvest tools. It is pre-built and checked in — you only need to regenerate it when you want fresher data.

### Regenerating

```bash
python3 scripts/generate_stock_universe_csv.py
```

This fetches the ICICI Security Master as the base universe, enriches it with Nifty constituent CSVs (Nifty Total Market, Nifty 500, MidSmallcap 400, Smallcap 500, Microcap 250), and adds corporate-group data from the CDSL repository by ISIN. Output defaults to `src/main/resources/stock-universe.csv`.

For rows still missing industry after Nifty enrichment, add `--nse-fallback` to query the NSE quote API:

```bash
python3 scripts/generate_stock_universe_csv.py --nse-fallback
```

For fully offline regeneration using locally downloaded source files:

```bash
python3 scripts/generate_stock_universe_csv.py \
  --security-master-source /tmp/SecurityMaster.zip \
  --nifty-source /tmp/ind_nifty500list.csv \
  --output src/main/resources/stock-universe.csv
```

See `scripts/README.md` for the full option reference.

---

## Zerodha Tradebook Import (FIFO Tax Reporting)

Kite API only exposes same-day trades. Historical FIFO LTCG/STCG reporting requires a Console tradebook CSV.

### Manual CSV download

1. Log into [Zerodha Console](https://console.zerodha.com/)
2. Go to **Reports → Tradebook**
3. Select the financial year segment (e.g., "Equity")
4. Click **Download** to get the CSV
5. Save it to `~/.broker-mcp/imports/` (or your configured `ZERODHA_TRADEBOOK_IMPORT_ROOT`)
6. The server imports the CSVs from this directory on startup

### Automated CSV download

Set in `.env`:

```env
ZERODHA_TRADEBOOK_AUTO_EXPORT=true
```

Then `./refresh_trading_sessions.sh zerodha` will download Zerodha tradebook history after login for `BROKER_TRADE_HISTORY_YEARS` years, split into 365-day CSV chunks when needed.

### Using the data

Tradebook CSVs in `~/.broker-mcp/imports/` are auto-imported on server startup — all matching CSVs in that directory are merged into the Zerodha tradebook store.

To enable the `import_zerodha_tradebook` MCP tool (manual mid-session imports) and order placement:

```env
BROKER_TOOLS_MODE=full
```

The `tax_harvest_report` tool returns a structured partial status when tradebook data is missing.

## Corporate Action Registry

Tax harvest uses a bundled seed file plus an external writable store for reviewed corporate actions:

- `SPLIT` and `BONUS` entries adjust FIFO lot quantity and per-share cost basis
- `ALLOTMENT` entries help classify unmatched IPO holdings that have no tradebook row
- external edits persist to `BROKER_CORPORATE_ACTIONS_STORE_PATH`

Not supported by this registry:

- `DEMERGER` cases are intentionally left in `needs_corporate_action_review`
- demergers create child lots from a parent holding and require cost-allocation research rather than a simple per-symbol quantity adjustment
- example: `ITCHOTELS` received from `ITC` in January 2025 should remain manual-review until dedicated demerger support exists

Default local path:

```env
BROKER_CORPORATE_ACTIONS_STORE_PATH=~/.broker-mcp/stock-corporate-actions.json
```

For Docker, mount a persistent data directory and point the store at `/data/stock-corporate-actions.json`.

Useful MCP tools:

- `list_corporate_actions`
- `upsert_corporate_action`

When tax harvest cannot reconcile a holding from available trade history, it now returns:

- `needs_corporate_action_review`
- `suspected_corporate_action_reasons`

---

## Available Tools

### Intelligence

| Tool | Description |
|------|-------------|
| `portfolio_snapshot` | Portfolio totals, movers, top holdings, and overall winners/losers |
| `stock_checkup` | Single-stock quote, current price, position, trend, and volume analysis |
| `portfolio_health` | Concentration, sector exposure, risk flags, and data coverage |
| `tax_harvest_report` | FY capital gains and tax-loss harvesting analysis |
| `market_pulse` | Nifty, Bank Nifty, and option-chain sentiment snapshot |

### Trading And Account

| Tool | Description |
|------|-------------|
| `breeze_get_funds` | Account balance and segment allocations |
| `breeze_session_status` | Check session state |
| `breeze_set_session` | Set the ICICI Breeze session |
| `zerodha_set_session` | Set the Zerodha access token |
| `import_zerodha_tradebook` | Persist Console tradebook CSV for FIFO tax reporting (`full` mode) |
| `list_corporate_actions` | Show the corporate-action registry used by tax harvest |
| `upsert_corporate_action` | Add or correct a reviewed split, bonus, or allotment entry (`full` mode) |
| `order_preview` | Preview charges and proceeds without placing an order |
| `execute_trade` | Preview or place a cash equity order |
| `set_stop_losses` | Preview or place stop-loss GTT orders |

Tools are **read-only by default**. Set `BROKER_TOOLS_MODE=full` to enable order placement and corporate-action updates.

---

## Useful MCP Client Prompts

These are written for general MCP clients. Paste them as-is, or swap in your own stock names, quantities, and constraints.

### Market snapshot

```
Give me a quick market brief for today using Nifty 50, Bank Nifty, and option-chain sentiment. Keep it concise and end with 3 actionable takeaways.

Compare Reliance Industries and HDFC Bank on current price, recent trend, and momentum. Tell me which looks stronger right now and why.

Do a full stock checkup on Tata Power. Include price, moving averages, momentum, volume trend, and key risks.
```

### Portfolio review

```
Summarize my portfolio with total value, top gainers, top losers, largest positions by weight, and available cash. Present it as a short investment review.

Review my current holdings and flag concentration risk, sector imbalance, and any positions with unusually large unrealized losses.

Which positions are contributing the most to my current portfolio risk? Rank them and explain the reason for each.
```

### Tax harvesting

```
Generate my capital gains tax report for the current financial year. Show realized LTCG, realized STCG, harvestable losses, and any data-coverage warnings.

Find tax-loss harvesting candidates in my portfolio. For each candidate, explain the likely tax impact, whether it looks LTCG or STCG, and anything that needs manual review.

Review my harvest candidates and tell me which 3 are the most useful to evaluate first. Prefer candidates with meaningful tax impact and clean broker/data coverage.
```

### Sell preview and broker routing

```
Preview a sell for one of my harvest candidates and explain the estimated charges, realized P&L, validation messages, and which broker the order is being routed through.

If a sell preview routes through a different broker than I expect, explain why that broker was selected and whether the holding appears to belong to another broker account.

Help me evaluate whether I should harvest a specific position today. Use the sell preview, estimated charges, realized P&L, and tax context, then give a cautious recommendation.
```

### Trading and execution (`full` mode required)

```
Preview a market buy for 100 shares of ICICI Bank. Show estimated charges, total cost, and whether there are any validation issues before placing the order.

Preview a market sell for 25 shares of Infosys and explain the expected proceeds, charges, and realized profit or loss.

Place a stop-loss on my TCS position at 5% below the current price. First show me the preview, then wait for confirmation before placing anything.
```

### Troubleshooting and diagnostics

```
My sell preview failed. Diagnose the likely cause using the broker response, routing choice, and validation messages, then tell me the safest next step.

Check whether my tax-harvest report has any partial coverage, missing tradebook history, or broker-specific limitations that could affect the result.

Explain any warnings or limitations in my current portfolio and tax reports in plain English, and tell me what I should fix first.
```

---

## Troubleshooting

**"Session not initialized"** — Run `./refresh_trading_sessions.sh`

**Browser doesn't open** — `pip install playwright && playwright install chromium`

**OTP not fetched from Gmail** — Check `GMAIL_USER` and `GMAIL_APP_PASSWORD` in `.env`

**Zerodha 2FA not automated** — Set `ZERODHA_TOTP_SECRET` in `.env`

**Claude Desktop doesn't restart** — Restart it manually. Auto-restart works on macOS and Windows.

**Wrong Python version** — `PYTHON_BIN=/usr/local/bin/python3.11 ./refresh_trading_sessions.sh`
**Corporate-action edits disappear in Docker** â€” mount a host directory to `/data` and set `BROKER_CORPORATE_ACTIONS_STORE_PATH=/data/stock-corporate-actions.json`
