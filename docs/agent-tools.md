# Agent tools

<!-- Generated from the tool registry by AgentToolsIT (HEJJE_REGEN_DOCS=1 ./gradlew test --tests '*AgentToolsIT*'). Do not edit. -->

Every agent capability is a typed tool (PRD 28). The scope is checked against the caller's credential, input is validated
against the schema below, output against the generated output schema, and every call is recorded in `agent_action`
with an `AGENT_TOOL_CALLED` audit event. See `docs/agents.md` for sessions, presets, the REST and MCP endpoints.

| Tool | Scope | Kind | Description |
|---|---|---|---|
| `calculate_position_size` | `risk:read` | read | Deterministic risk-based quantity: floor(risk / \|entry - stop\|) rounded down to whole lots, optionally capped. Give the instrument (for its lot size) or lotSize. |
| `cancel_order_intent` | `orders:prepare` | transactional | Asks a human to approve cancelling an open order. |
| `close_position_intent` | `orders:prepare` | transactional | Asks a human to approve closing the open position in an instrument. |
| `compare_strategies` | `strategies:read` | read | Side-by-side comparison of 2 to 6 strategy versions: trades, win rate, profit factor, expectancy (R), max drawdown (R), similar-regime performance and Hejje Score. |
| `compare_strategy_versions` | `strategies:read` | read | Compare two versions of one strategy: both rows, metric deltas and a templated verdict. |
| `get_account_risk` | `risk:read` | read | Account risk dashboard (PRD 54): P&L vs the daily loss limit, exposure, open positions, trades today, consecutive losses, margin use and the kill switch. |
| `get_audit_trail` | `admin` | read | Audit events, newest first, filtered by order id, event type or start date (at most 50). |
| `get_event_calendar` | `market:read` | read | Market and instrument events (holidays, expiries, results, RBI/FOMC/CPI) between two dates (default the next 7 days, at most 62), plus the instrument's current event risk when an instrument is given. |
| `get_market_regime` | `market:read` | read | Current market regime labels (trend, volatility, opening, breadth, intraday structure, event environment) with one evidence sentence per dimension, from the deterministic regime classifier. |
| `get_market_snapshot` | `market:read` | read | Latest quote (last price, bid/ask, volume, open interest, staleness) for one or up to 20 instruments. |
| `get_news_context` | `market:read` | read | News bias for an instrument (score -1..1, label, evidence per story) plus the last 24 hours of matched headlines. |
| `get_orders` | `market:read` | read | Today's orders in the current execution mode, optionally filtered by state (newest first). |
| `get_pnl_breakdown` | `market:read` | read | Realized P&L of closed round trips (net of fees, rupees) grouped by strategy, version, instrument, weekday, hour or market regime, between two dates (default today, at most 92 days). |
| `get_positions` | `market:read` | read | Open positions in the current execution mode with average price, last price and unrealized/realized P&L (rupees). |
| `get_pulse` | `market:read` | read | Technical Pulse (direction, strength, -100..100 score, per-rule components) and Market Pulse rows (regime, volatility, breadth, sector strength). |
| `get_strategy` | `strategies:read` | read | One strategy version (default the latest): rules in words (entry/exit conditions, stop, target, trailing, window), regime preferences, event rules, the best instrument's score breakdown and its deployments. |
| `get_strategy_backtest` | `strategies:read` | read | A backtest by id, or the base backtest of a version: metrics overall and per split (IS / validation / OOS), quality warnings, data coverage and the result hash. |
| `get_strategy_rankings` | `strategies:read` | read | Today's ranked recommendations as PRD 29 decision objects (score, TRADE / TRADE_WITH_CAUTION / WAIT / AVOID, direction, entry/stop/target, risk, regime, news bias, event risk, hard blocks, cautions) and the best one. |
| `get_strategy_signal` | `strategies:read` | read | A signal by id, today's signals with a given status, or (default) every active signal: side, reference price, stop, target, validity and the evidence that fired it. |
| `get_trades` | `market:read` | read | Fills between two dates (default today, at most 31 days) in the current execution mode. |
| `list_strategies` | `strategies:read` | read | Every strategy in the library with its latest version, lifecycle status and headline Hejje Score. |
| `modify_order_intent` | `orders:prepare` | transactional | Asks a human to approve modifying an open order (quantity, order type, limit or trigger price). |
| `prepare_order` | `orders:prepare` | read | Dry run of an order: Hejje sizes it from the rupee risk and stop (or the signal), runs the risk checks and the approval policy, and returns the proposal. Nothing is created; use submit_order_intent to ask a human to approve it. |
| `submit_order_intent` | `orders:prepare` | transactional | Creates an order proposal (PROPOSED intent) and an approval request for a human; the order is placed only if a human approves it in the Approvals inbox before it expires. Same input as prepare_order plus a rationale. |

## `calculate_position_size`

Deterministic risk-based quantity: floor(risk / |entry - stop|) rounded down to whole lots, optionally capped. Give the instrument (for its lot size) or lotSize.

Scope `risk:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "entry" : {
      "type" : "number",
      "minimum" : 0
    },
    "stop" : {
      "type" : "number",
      "minimum" : 0
    },
    "riskRupees" : {
      "type" : "number",
      "minimum" : 1
    },
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    },
    "lotSize" : {
      "type" : "integer",
      "minimum" : 1
    },
    "maxQuantity" : {
      "type" : "integer",
      "minimum" : 1
    }
  },
  "required" : [ "entry", "stop", "riskRupees" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "quantity" : {
      "type" : "integer"
    },
    "riskPerUnit" : {
      "type" : "number"
    },
    "totalRisk" : {
      "type" : "number"
    },
    "lotSize" : {
      "type" : "integer"
    },
    "notes" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    }
  },
  "required" : [ "quantity", "lotSize" ]
}
```

## `cancel_order_intent`

Asks a human to approve cancelling an open order.

Scope `orders:prepare`, transactional.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "orderId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "rationale" : {
      "type" : "string",
      "maxLength" : 500,
      "description" : "Why, in one or two sentences, shown to the approver"
    }
  },
  "required" : [ "orderId" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "approvalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "kind" : {
      "type" : "string"
    },
    "status" : {
      "type" : "string"
    },
    "summary" : {
      "type" : "string"
    },
    "intentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "expiresAt" : {
      "type" : "string"
    },
    "policyDecision" : {
      "type" : "string"
    },
    "policyReason" : {
      "type" : "string"
    },
    "message" : {
      "type" : "string"
    }
  }
}
```

## `close_position_intent`

Asks a human to approve closing the open position in an instrument.

Scope `orders:prepare`, transactional.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    },
    "product" : {
      "type" : "string",
      "enum" : [ "MIS", "CNC", "NRML" ]
    },
    "rationale" : {
      "type" : "string",
      "maxLength" : 500,
      "description" : "Why, in one or two sentences, shown to the approver"
    }
  },
  "required" : [ "instrument" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "approvalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "kind" : {
      "type" : "string"
    },
    "status" : {
      "type" : "string"
    },
    "summary" : {
      "type" : "string"
    },
    "intentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "expiresAt" : {
      "type" : "string"
    },
    "policyDecision" : {
      "type" : "string"
    },
    "policyReason" : {
      "type" : "string"
    },
    "message" : {
      "type" : "string"
    }
  }
}
```

## `compare_strategies`

Side-by-side comparison of 2 to 6 strategy versions: trades, win rate, profit factor, expectancy (R), max drawdown (R), similar-regime performance and Hejje Score.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "versionIds" : {
      "type" : "array",
      "items" : {
        "type" : "string",
        "format" : "uuid"
      },
      "minItems" : 2,
      "maxItems" : 6
    }
  },
  "required" : [ "versionIds" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "rows" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "versionId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "slug" : {
            "type" : "string"
          },
          "version" : {
            "type" : "integer"
          },
          "status" : {
            "type" : "string"
          },
          "backtestId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "trades" : {
            "type" : "integer"
          },
          "winRate" : {
            "type" : "number"
          },
          "profitFactor" : {
            "type" : "number"
          },
          "expectancyR" : {
            "type" : "number"
          },
          "maxDrawdownR" : {
            "type" : "number"
          },
          "similarRegimePerformance" : {
            "type" : "string"
          },
          "hejjeScore" : {
            "type" : "integer"
          }
        },
        "required" : [ "version" ]
      }
    }
  }
}
```

## `compare_strategy_versions`

Compare two versions of one strategy: both rows, metric deltas and a templated verdict.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "strategy" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Strategy id or slug (e.g. nifty_orb)"
    },
    "a" : {
      "type" : "integer",
      "minimum" : 1
    },
    "b" : {
      "type" : "integer",
      "minimum" : 1
    }
  },
  "required" : [ "strategy", "a", "b" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "a" : {
      "type" : "object",
      "properties" : {
        "versionId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "strategyId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "slug" : {
          "type" : "string"
        },
        "version" : {
          "type" : "integer"
        },
        "status" : {
          "type" : "string"
        },
        "backtestId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "trades" : {
          "type" : "integer"
        },
        "winRate" : {
          "type" : "number"
        },
        "profitFactor" : {
          "type" : "number"
        },
        "expectancyR" : {
          "type" : "number"
        },
        "maxDrawdownR" : {
          "type" : "number"
        },
        "similarRegimePerformance" : {
          "type" : "string"
        },
        "hejjeScore" : {
          "type" : "integer"
        }
      },
      "required" : [ "version" ]
    },
    "b" : {
      "type" : "object",
      "properties" : {
        "versionId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "strategyId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "slug" : {
          "type" : "string"
        },
        "version" : {
          "type" : "integer"
        },
        "status" : {
          "type" : "string"
        },
        "backtestId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "trades" : {
          "type" : "integer"
        },
        "winRate" : {
          "type" : "number"
        },
        "profitFactor" : {
          "type" : "number"
        },
        "expectancyR" : {
          "type" : "number"
        },
        "maxDrawdownR" : {
          "type" : "number"
        },
        "similarRegimePerformance" : {
          "type" : "string"
        },
        "hejjeScore" : {
          "type" : "integer"
        }
      },
      "required" : [ "version" ]
    },
    "deltas" : {
      "type" : "array",
      "items" : {
        "type" : "object"
      }
    },
    "verdict" : {
      "type" : "string"
    }
  }
}
```

## `get_account_risk`

Account risk dashboard (PRD 54): P&L vs the daily loss limit, exposure, open positions, trades today, consecutive losses, margin use and the kill switch.

Scope `risk:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : { },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "mode" : {
      "type" : "string"
    },
    "realizedPnl" : {
      "type" : "number"
    },
    "unrealizedPnl" : {
      "type" : "number"
    },
    "netPnl" : {
      "type" : "number"
    },
    "dailyLossLimit" : {
      "type" : "number"
    },
    "grossExposure" : {
      "type" : "number"
    },
    "maxGrossExposure" : {
      "type" : "number"
    },
    "openPositions" : {
      "type" : "integer"
    },
    "maxOpenPositions" : {
      "type" : "integer"
    },
    "tradesToday" : {
      "type" : "integer"
    },
    "maxTradesPerDay" : {
      "type" : "integer"
    },
    "consecutiveLosses" : {
      "type" : "integer"
    },
    "marginUsedPct" : {
      "type" : "number"
    },
    "stopNewOrders" : {
      "type" : "boolean"
    },
    "killSwitchReason" : {
      "type" : "string"
    }
  },
  "required" : [ "openPositions", "maxOpenPositions", "tradesToday", "maxTradesPerDay", "consecutiveLosses", "stopNewOrders" ]
}
```

## `get_audit_trail`

Audit events, newest first, filtered by order id, event type or start date (at most 50).

Scope `admin`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "orderId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "type" : {
      "type" : "string",
      "enum" : [ "SIGNAL_CREATED", "STRATEGY_RECOMMENDED", "AGENT_RECOMMENDED", "USER_APPROVED", "RISK_CHECK_PASSED", "RISK_CHECK_REJECTED", "ORDER_SUBMITTED", "BROKER_ACCEPTED", "ORDER_FILLED", "STOP_MODIFIED", "POSITION_CLOSED", "STRATEGY_PAUSED", "KILL_SWITCH_ENABLED", "AUTH_LOGIN", "AUTH_LOGIN_FAILED", "CLIENT_CREATED", "CLIENT_REVOKED", "EGRESS_IP_STATUS_CHANGED", "INSTRUMENTS_SYNCED", "BROKER_CONNECTED", "BROKER_LOGIN_FAILED", "BROKER_DISCONNECTED", "BROKER_SESSION_EXPIRED", "BROKER_LOGGED_OUT", "ORDER_INTENT_CREATED", "RISK_CHECK_FAILED", "ORDER_CANCELLED", "ORDER_REJECTED", "ORDER_MODIFIED", "ILLEGAL_TRANSITION", "KILL_SWITCH_DISARMED", "RISK_LIMITS_UPDATED", "RECONCILIATION_ISSUE_DETECTED", "RECONCILIATION_ISSUE_RESOLVED", "EXTERNAL_ORDER_IMPORTED", "EXECUTOR_LEASE_ACQUIRED", "EXECUTION_ENABLED", "STRATEGY_CREATED", "STRATEGY_VERSION_CREATED", "STRATEGY_STATUS_CHANGED", "STRATEGY_DEPLOYED", "STRATEGY_DEPLOYMENT_UPDATED", "SIGNAL_EXPIRED", "SIGNAL_SKIPPED", "SIGNAL_PREPARED", "STRATEGY_STOP_PLACED", "STRATEGY_EXIT_TRIGGERED", "STOP_MISSING", "EVENT_ADDED", "EVENTS_IMPORTED", "EVENTS_REFRESHED", "LLM_BUDGET_EXCEEDED", "AGENT_TOOL_CALLED", "USER_REJECTED", "APPROVAL_EXPIRED", "APPROVAL_FAILED", "POLICY_UPDATED" ]
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 50
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "total" : {
      "type" : "integer"
    },
    "events" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "auditId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "ts" : {
            "type" : "string"
          },
          "type" : {
            "type" : "string"
          },
          "actorType" : {
            "type" : "string"
          },
          "actorId" : {
            "type" : "string"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "signalId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "orderIntentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "orderId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "brokerRef" : {
            "type" : "string"
          },
          "clientSource" : {
            "type" : "string"
          },
          "payload" : {
            "type" : "object"
          }
        }
      }
    }
  },
  "required" : [ "total" ]
}
```

## `get_event_calendar`

Market and instrument events (holidays, expiries, results, RBI/FOMC/CPI) between two dates (default the next 7 days, at most 62), plus the instrument's current event risk when an instrument is given.

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    },
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "enabled" : {
      "type" : "boolean"
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    },
    "events" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "eventId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "type" : {
            "type" : "string"
          },
          "scope" : {
            "type" : "string"
          },
          "symbol" : {
            "type" : "string"
          },
          "title" : {
            "type" : "string"
          },
          "startsAt" : {
            "type" : "string"
          },
          "endsAt" : {
            "type" : "string"
          },
          "allDay" : {
            "type" : "boolean"
          },
          "confidence" : {
            "type" : "number"
          }
        },
        "required" : [ "allDay", "confidence" ]
      }
    },
    "risk" : {
      "type" : "object",
      "properties" : {
        "available" : {
          "type" : "boolean"
        },
        "level" : {
          "type" : "string"
        },
        "nextEventId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "nextEvent" : {
          "type" : "string"
        },
        "minutesTo" : {
          "type" : "integer"
        },
        "evidence" : {
          "type" : "array",
          "items" : {
            "type" : "string"
          }
        }
      },
      "required" : [ "available" ]
    }
  },
  "required" : [ "enabled" ]
}
```

## `get_market_regime`

Current market regime labels (trend, volatility, opening, breadth, intraday structure, event environment) with one evidence sentence per dimension, from the deterministic regime classifier.

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : { },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "available" : {
      "type" : "boolean"
    },
    "date" : {
      "type" : "string",
      "format" : "date"
    },
    "asOf" : {
      "type" : "string"
    },
    "trend" : {
      "type" : "string"
    },
    "volatility" : {
      "type" : "string"
    },
    "opening" : {
      "type" : "string"
    },
    "breadth" : {
      "type" : "string"
    },
    "intradayStructure" : {
      "type" : "string"
    },
    "eventEnvironment" : {
      "type" : "string"
    },
    "evidence" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "classifierVersion" : {
      "type" : "string"
    },
    "finalLabel" : {
      "type" : "boolean"
    }
  },
  "required" : [ "available", "finalLabel" ]
}
```

## `get_market_snapshot`

Latest quote (last price, bid/ask, volume, open interest, staleness) for one or up to 20 instruments.

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    },
    "instruments" : {
      "type" : "array",
      "items" : {
        "type" : "string",
        "minLength" : 1,
        "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
      },
      "minItems" : 1,
      "maxItems" : 20
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "asOf" : {
      "type" : "string"
    },
    "quotes" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "symbol" : {
            "type" : "string"
          },
          "lastPrice" : {
            "type" : "number"
          },
          "bid" : {
            "type" : "number"
          },
          "ask" : {
            "type" : "number"
          },
          "volume" : {
            "type" : "integer"
          },
          "oi" : {
            "type" : "integer"
          },
          "ts" : {
            "type" : "string"
          },
          "stale" : {
            "type" : "boolean"
          }
        },
        "required" : [ "volume", "oi", "stale" ]
      }
    },
    "noQuote" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    }
  }
}
```

## `get_news_context`

News bias for an instrument (score -1..1, label, evidence per story) plus the last 24 hours of matched headlines.

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    }
  },
  "required" : [ "instrument" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "instrumentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "symbol" : {
      "type" : "string"
    },
    "available" : {
      "type" : "boolean"
    },
    "score" : {
      "type" : "number"
    },
    "label" : {
      "type" : "string"
    },
    "items" : {
      "type" : "integer"
    },
    "evidence" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "headlines" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "newsId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "title" : {
            "type" : "string"
          },
          "url" : {
            "type" : "string"
          },
          "publishedAt" : {
            "type" : "string"
          }
        }
      }
    }
  },
  "required" : [ "available", "score", "items" ]
}
```

## `get_orders`

Today's orders in the current execution mode, optionally filtered by state (newest first).

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "state" : {
      "type" : "string",
      "enum" : [ "CREATED", "VALIDATING", "RISK_REJECTED", "READY", "SUBMITTING", "BROKER_ACCEPTED", "OPEN", "PARTIALLY_FILLED", "FILLED", "MODIFY_PENDING", "CANCEL_PENDING", "CANCELLED", "REJECTED", "UNKNOWN", "RECONCILING" ]
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 100
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "mode" : {
      "type" : "string"
    },
    "orders" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "orderId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "intentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrument" : {
            "type" : "string"
          },
          "side" : {
            "type" : "string"
          },
          "quantity" : {
            "type" : "integer"
          },
          "filledQuantity" : {
            "type" : "integer"
          },
          "averagePrice" : {
            "type" : "number"
          },
          "orderType" : {
            "type" : "string"
          },
          "product" : {
            "type" : "string"
          },
          "limitPrice" : {
            "type" : "number"
          },
          "triggerPrice" : {
            "type" : "number"
          },
          "state" : {
            "type" : "string"
          },
          "role" : {
            "type" : "string"
          },
          "placedAt" : {
            "type" : "string"
          },
          "updatedAt" : {
            "type" : "string"
          }
        },
        "required" : [ "quantity", "filledQuantity" ]
      }
    }
  }
}
```

## `get_pnl_breakdown`

Realized P&L of closed round trips (net of fees, rupees) grouped by strategy, version, instrument, weekday, hour or market regime, between two dates (default today, at most 92 days).

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "groupBy" : {
      "type" : "string",
      "enum" : [ "strategy", "version", "instrument", "weekday", "hour", "regime" ]
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "mode" : {
      "type" : "string"
    },
    "groupBy" : {
      "type" : "string"
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    },
    "trades" : {
      "type" : "integer"
    },
    "netPnl" : {
      "type" : "number"
    },
    "buckets" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "key" : {
            "type" : "string"
          },
          "label" : {
            "type" : "string"
          },
          "trades" : {
            "type" : "integer"
          },
          "wins" : {
            "type" : "integer"
          },
          "grossPnl" : {
            "type" : "number"
          },
          "fees" : {
            "type" : "number"
          },
          "netPnl" : {
            "type" : "number"
          },
          "winRate" : {
            "type" : "number"
          },
          "averageR" : {
            "type" : "number"
          }
        },
        "required" : [ "trades", "wins", "winRate" ]
      }
    }
  },
  "required" : [ "trades" ]
}
```

## `get_positions`

Open positions in the current execution mode with average price, last price and unrealized/realized P&L (rupees).

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : { },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "mode" : {
      "type" : "string"
    },
    "positions" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "positionId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrument" : {
            "type" : "string"
          },
          "product" : {
            "type" : "string"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "netQuantity" : {
            "type" : "integer"
          },
          "averagePrice" : {
            "type" : "number"
          },
          "lastPrice" : {
            "type" : "number"
          },
          "unrealizedPnl" : {
            "type" : "number"
          },
          "realizedPnl" : {
            "type" : "number"
          },
          "fees" : {
            "type" : "number"
          },
          "openedAt" : {
            "type" : "string"
          }
        },
        "required" : [ "netQuantity" ]
      }
    }
  }
}
```

## `get_pulse`

Technical Pulse (direction, strength, -100..100 score, per-rule components) and Market Pulse rows (regime, volatility, breadth, sector strength).

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : { },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "available" : {
      "type" : "boolean"
    },
    "date" : {
      "type" : "string",
      "format" : "date"
    },
    "asOf" : {
      "type" : "string"
    },
    "direction" : {
      "type" : "string"
    },
    "strength" : {
      "type" : "string"
    },
    "score" : {
      "type" : "integer"
    },
    "coverage" : {
      "type" : "number"
    },
    "components" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "name" : {
            "type" : "string"
          },
          "value" : {
            "type" : "number"
          },
          "contribution" : {
            "type" : "number"
          },
          "evidence" : {
            "type" : "string"
          }
        },
        "required" : [ "contribution" ]
      }
    },
    "evidence" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "regime" : {
      "type" : "string"
    },
    "volatility" : {
      "type" : "string"
    },
    "breadth" : {
      "type" : "string"
    },
    "sectors" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "name" : {
            "type" : "string"
          },
          "symbol" : {
            "type" : "string"
          },
          "label" : {
            "type" : "string"
          },
          "changePct" : {
            "type" : "number"
          },
          "relativePct" : {
            "type" : "number"
          }
        }
      }
    }
  },
  "required" : [ "available" ]
}
```

## `get_strategy`

One strategy version (default the latest): rules in words (entry/exit conditions, stop, target, trailing, window), regime preferences, event rules, the best instrument's score breakdown and its deployments.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "strategy" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Strategy id or slug (e.g. nifty_orb)"
    },
    "version" : {
      "type" : "integer",
      "minimum" : 1
    }
  },
  "required" : [ "strategy" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "strategyId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "slug" : {
      "type" : "string"
    },
    "name" : {
      "type" : "string"
    },
    "family" : {
      "type" : "string"
    },
    "versionId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "version" : {
      "type" : "integer"
    },
    "status" : {
      "type" : "string"
    },
    "changeNote" : {
      "type" : "string"
    },
    "description" : {
      "type" : "string"
    },
    "timeframe" : {
      "type" : "string"
    },
    "direction" : {
      "type" : "string"
    },
    "universe" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "entryMode" : {
      "type" : "string"
    },
    "entryConditions" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "exitMode" : {
      "type" : "string"
    },
    "exitConditions" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "stop" : {
      "type" : "string"
    },
    "target" : {
      "type" : "string"
    },
    "trailingStop" : {
      "type" : "string"
    },
    "tradeWindow" : {
      "type" : "string"
    },
    "forceExitTime" : {
      "type" : "string"
    },
    "maxTradesPerDay" : {
      "type" : "integer"
    },
    "maxHoldingMinutes" : {
      "type" : "integer"
    },
    "regimePreferences" : {
      "type" : "object"
    },
    "eventRules" : {
      "type" : "string"
    },
    "score" : {
      "type" : "object",
      "properties" : {
        "finalScore" : {
          "type" : "integer"
        },
        "base" : {
          "type" : "number"
        },
        "cap" : {
          "type" : "string"
        },
        "instrumentId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "instrument" : {
          "type" : "string"
        },
        "backtestId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "computedAt" : {
          "type" : "string"
        },
        "components" : {
          "type" : "array",
          "items" : {
            "type" : "object",
            "properties" : {
              "name" : {
                "type" : "string"
              },
              "weight" : {
                "type" : "number"
              },
              "score" : {
                "type" : "number"
              },
              "contribution" : {
                "type" : "number"
              }
            },
            "required" : [ "weight", "score", "contribution" ]
          }
        },
        "adjustments" : {
          "type" : "array",
          "items" : {
            "type" : "object",
            "properties" : {
              "name" : {
                "type" : "string"
              },
              "delta" : {
                "type" : "integer"
              },
              "evidence" : {
                "type" : "array",
                "items" : {
                  "type" : "string"
                }
              }
            },
            "required" : [ "delta" ]
          }
        }
      },
      "required" : [ "finalScore", "base" ]
    },
    "deployments" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "deploymentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "mode" : {
            "type" : "string"
          },
          "enabled" : {
            "type" : "boolean"
          },
          "autonomyLevel" : {
            "type" : "integer"
          },
          "instruments" : {
            "type" : "array",
            "items" : {
              "type" : "string"
            }
          }
        },
        "required" : [ "enabled", "autonomyLevel" ]
      }
    }
  },
  "required" : [ "version", "maxTradesPerDay" ]
}
```

## `get_strategy_backtest`

A backtest by id, or the base backtest of a version: metrics overall and per split (IS / validation / OOS), quality warnings, data coverage and the result hash.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "backtestId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "versionId" : {
      "type" : "string",
      "format" : "uuid"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "backtestId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "versionId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "status" : {
      "type" : "string"
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    },
    "timeframe" : {
      "type" : "string"
    },
    "slippageBps" : {
      "type" : "integer"
    },
    "metrics" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "split" : {
            "type" : "string"
          },
          "trades" : {
            "type" : "integer"
          },
          "winRate" : {
            "type" : "number"
          },
          "profitFactor" : {
            "type" : "number"
          },
          "expectancyR" : {
            "type" : "number"
          },
          "maxDrawdownR" : {
            "type" : "number"
          },
          "netPnl" : {
            "type" : "number"
          },
          "sharpe" : {
            "type" : "number"
          }
        },
        "required" : [ "trades", "winRate", "expectancyR", "maxDrawdownR" ]
      }
    },
    "warnings" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "code" : {
            "type" : "string"
          },
          "severity" : {
            "type" : "string"
          },
          "message" : {
            "type" : "string"
          }
        }
      }
    },
    "sessionsExpected" : {
      "type" : "integer"
    },
    "sessionsWithData" : {
      "type" : "integer"
    },
    "resultHash" : {
      "type" : "string"
    },
    "finishedAt" : {
      "type" : "string"
    }
  },
  "required" : [ "slippageBps", "sessionsExpected", "sessionsWithData" ]
}
```

## `get_strategy_rankings`

Today's ranked recommendations as PRD 29 decision objects (score, TRADE / TRADE_WITH_CAUTION / WAIT / AVOID, direction, entry/stop/target, risk, regime, news bias, event risk, hard blocks, cautions) and the best one.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 20
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "asOf" : {
      "type" : "string"
    },
    "best" : {
      "type" : "object",
      "properties" : {
        "versionId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "strategyId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "strategy" : {
          "type" : "string"
        },
        "version" : {
          "type" : "integer"
        },
        "instrumentId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "instrument" : {
          "type" : "string"
        },
        "score" : {
          "type" : "integer"
        },
        "decision" : {
          "type" : "string"
        },
        "direction" : {
          "type" : "string"
        },
        "signalId" : {
          "type" : "string",
          "format" : "uuid"
        },
        "signalValidUntil" : {
          "type" : "string"
        },
        "entry" : {
          "type" : "number"
        },
        "stop" : {
          "type" : "number"
        },
        "target" : {
          "type" : "number"
        },
        "quantity" : {
          "type" : "integer"
        },
        "riskRupees" : {
          "type" : "number"
        },
        "regime" : {
          "type" : "string"
        },
        "newsBias" : {
          "type" : "number"
        },
        "eventRisk" : {
          "type" : "string"
        },
        "nextEvent" : {
          "type" : "string"
        },
        "hardBlocks" : {
          "type" : "array",
          "items" : {
            "type" : "string"
          }
        },
        "cautions" : {
          "type" : "array",
          "items" : {
            "type" : "string"
          }
        },
        "context" : {
          "type" : "array",
          "items" : {
            "type" : "object",
            "properties" : {
              "name" : {
                "type" : "string"
              },
              "status" : {
                "type" : "string"
              },
              "value" : {
                "type" : "string"
              },
              "delta" : {
                "type" : "integer"
              }
            }
          }
        }
      },
      "required" : [ "version" ]
    },
    "ranked" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "versionId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "strategy" : {
            "type" : "string"
          },
          "version" : {
            "type" : "integer"
          },
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrument" : {
            "type" : "string"
          },
          "score" : {
            "type" : "integer"
          },
          "decision" : {
            "type" : "string"
          },
          "direction" : {
            "type" : "string"
          },
          "signalId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "signalValidUntil" : {
            "type" : "string"
          },
          "entry" : {
            "type" : "number"
          },
          "stop" : {
            "type" : "number"
          },
          "target" : {
            "type" : "number"
          },
          "quantity" : {
            "type" : "integer"
          },
          "riskRupees" : {
            "type" : "number"
          },
          "regime" : {
            "type" : "string"
          },
          "newsBias" : {
            "type" : "number"
          },
          "eventRisk" : {
            "type" : "string"
          },
          "nextEvent" : {
            "type" : "string"
          },
          "hardBlocks" : {
            "type" : "array",
            "items" : {
              "type" : "string"
            }
          },
          "cautions" : {
            "type" : "array",
            "items" : {
              "type" : "string"
            }
          },
          "context" : {
            "type" : "array",
            "items" : {
              "type" : "object",
              "properties" : {
                "name" : {
                  "type" : "string"
                },
                "status" : {
                  "type" : "string"
                },
                "value" : {
                  "type" : "string"
                },
                "delta" : {
                  "type" : "integer"
                }
              }
            }
          }
        },
        "required" : [ "version" ]
      }
    },
    "noTrade" : {
      "type" : "string"
    }
  }
}
```

## `get_strategy_signal`

A signal by id, today's signals with a given status, or (default) every active signal: side, reference price, stop, target, validity and the evidence that fired it.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "signalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "status" : {
      "type" : "string",
      "enum" : [ "ACTIVE", "PREPARED", "EXECUTED", "EXPIRED", "SKIPPED", "BLOCKED" ]
    },
    "limit" : {
      "type" : "integer",
      "minimum" : 1,
      "maximum" : 50
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "signals" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "signalId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "versionId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "deploymentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrument" : {
            "type" : "string"
          },
          "mode" : {
            "type" : "string"
          },
          "side" : {
            "type" : "string"
          },
          "referencePrice" : {
            "type" : "number"
          },
          "stop" : {
            "type" : "number"
          },
          "target" : {
            "type" : "number"
          },
          "riskPerUnit" : {
            "type" : "number"
          },
          "barTime" : {
            "type" : "string"
          },
          "validUntil" : {
            "type" : "string"
          },
          "status" : {
            "type" : "string"
          },
          "note" : {
            "type" : "string"
          },
          "evidence" : {
            "type" : "array",
            "items" : {
              "type" : "object"
            }
          }
        }
      }
    }
  }
}
```

## `get_trades`

Fills between two dates (default today, at most 31 days) in the current execution mode.

Scope `market:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "mode" : {
      "type" : "string"
    },
    "from" : {
      "type" : "string",
      "format" : "date"
    },
    "to" : {
      "type" : "string",
      "format" : "date"
    },
    "trades" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "tradeId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "orderId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrumentId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "instrument" : {
            "type" : "string"
          },
          "side" : {
            "type" : "string"
          },
          "quantity" : {
            "type" : "integer"
          },
          "price" : {
            "type" : "number"
          },
          "ts" : {
            "type" : "string"
          },
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          }
        },
        "required" : [ "quantity" ]
      }
    }
  }
}
```

## `list_strategies`

Every strategy in the library with its latest version, lifecycle status and headline Hejje Score.

Scope `strategies:read`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : { },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "strategies" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "strategyId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "slug" : {
            "type" : "string"
          },
          "name" : {
            "type" : "string"
          },
          "family" : {
            "type" : "string"
          },
          "latestVersion" : {
            "type" : "integer"
          },
          "latestVersionId" : {
            "type" : "string",
            "format" : "uuid"
          },
          "latestStatus" : {
            "type" : "string"
          },
          "hejjeScore" : {
            "type" : "integer"
          }
        },
        "required" : [ "latestVersion" ]
      }
    }
  }
}
```

## `modify_order_intent`

Asks a human to approve modifying an open order (quantity, order type, limit or trigger price).

Scope `orders:prepare`, transactional.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "orderId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "quantity" : {
      "type" : "integer",
      "minimum" : 1
    },
    "orderType" : {
      "type" : "string",
      "enum" : [ "MARKET", "LIMIT", "SL", "SL_M" ]
    },
    "limitPrice" : {
      "type" : "number",
      "minimum" : 0
    },
    "triggerPrice" : {
      "type" : "number",
      "minimum" : 0
    },
    "rationale" : {
      "type" : "string",
      "maxLength" : 500,
      "description" : "Why, in one or two sentences, shown to the approver"
    }
  },
  "required" : [ "orderId" ],
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "approvalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "kind" : {
      "type" : "string"
    },
    "status" : {
      "type" : "string"
    },
    "summary" : {
      "type" : "string"
    },
    "intentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "expiresAt" : {
      "type" : "string"
    },
    "policyDecision" : {
      "type" : "string"
    },
    "policyReason" : {
      "type" : "string"
    },
    "message" : {
      "type" : "string"
    }
  }
}
```

## `prepare_order`

Dry run of an order: Hejje sizes it from the rupee risk and stop (or the signal), runs the risk checks and the approval policy, and returns the proposal. Nothing is created; use submit_order_intent to ask a human to approve it.

Scope `orders:prepare`, read-only.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "signalId" : {
      "type" : "string",
      "format" : "uuid",
      "description" : "Prepare the order for this active signal (other fields are then ignored)"
    },
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    },
    "side" : {
      "type" : "string",
      "enum" : [ "BUY", "SELL" ]
    },
    "riskRupees" : {
      "type" : "number",
      "minimum" : 1,
      "description" : "Maximum loss in rupees if the stop is hit; Hejje sizes the quantity from it"
    },
    "entry" : {
      "type" : "number",
      "minimum" : 0,
      "description" : "Reference entry price (default the last price)"
    },
    "stop" : {
      "type" : "number",
      "minimum" : 0
    },
    "target" : {
      "type" : "number",
      "minimum" : 0
    },
    "product" : {
      "type" : "string",
      "enum" : [ "MIS", "CNC", "NRML" ]
    },
    "strategy" : {
      "type" : "string",
      "description" : "Strategy id or slug the order belongs to (its deployment's autonomy level applies)"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "kind" : {
      "type" : "string"
    },
    "signalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "strategyId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "strategy" : {
      "type" : "string"
    },
    "versionId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "instrumentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "instrument" : {
      "type" : "string"
    },
    "side" : {
      "type" : "string"
    },
    "quantity" : {
      "type" : "integer"
    },
    "orderType" : {
      "type" : "string"
    },
    "product" : {
      "type" : "string"
    },
    "entry" : {
      "type" : "number"
    },
    "stop" : {
      "type" : "number"
    },
    "target" : {
      "type" : "number"
    },
    "riskRupees" : {
      "type" : "number"
    },
    "maxRisk" : {
      "type" : "number"
    },
    "autonomyLevel" : {
      "type" : "integer"
    },
    "eventRisk" : {
      "type" : "string"
    },
    "score" : {
      "type" : "integer"
    },
    "newStrategyVersion" : {
      "type" : "boolean"
    },
    "riskOutcome" : {
      "type" : "string"
    },
    "riskChecks" : {
      "type" : "array",
      "items" : {
        "type" : "object",
        "properties" : {
          "name" : {
            "type" : "string"
          },
          "passed" : {
            "type" : "boolean"
          },
          "observed" : {
            "type" : "string"
          },
          "limit" : {
            "type" : "string"
          },
          "message" : {
            "type" : "string"
          }
        },
        "required" : [ "passed" ]
      }
    },
    "policyDecision" : {
      "type" : "string"
    },
    "policyRule" : {
      "type" : "string"
    },
    "policyReason" : {
      "type" : "string"
    },
    "notes" : {
      "type" : "array",
      "items" : {
        "type" : "string"
      }
    },
    "summary" : {
      "type" : "string"
    }
  },
  "required" : [ "quantity", "newStrategyVersion" ]
}
```

## `submit_order_intent`

Creates an order proposal (PROPOSED intent) and an approval request for a human; the order is placed only if a human approves it in the Approvals inbox before it expires. Same input as prepare_order plus a rationale.

Scope `orders:prepare`, transactional.

Input schema:

```json
{
  "type" : "object",
  "properties" : {
    "signalId" : {
      "type" : "string",
      "format" : "uuid",
      "description" : "Prepare the order for this active signal (other fields are then ignored)"
    },
    "instrument" : {
      "type" : "string",
      "minLength" : 1,
      "description" : "Hejje symbol such as NSE:RELIANCE or INDEX:NIFTY 50, or an instrument id"
    },
    "side" : {
      "type" : "string",
      "enum" : [ "BUY", "SELL" ]
    },
    "riskRupees" : {
      "type" : "number",
      "minimum" : 1,
      "description" : "Maximum loss in rupees if the stop is hit; Hejje sizes the quantity from it"
    },
    "entry" : {
      "type" : "number",
      "minimum" : 0,
      "description" : "Reference entry price (default the last price)"
    },
    "stop" : {
      "type" : "number",
      "minimum" : 0
    },
    "target" : {
      "type" : "number",
      "minimum" : 0
    },
    "product" : {
      "type" : "string",
      "enum" : [ "MIS", "CNC", "NRML" ]
    },
    "strategy" : {
      "type" : "string",
      "description" : "Strategy id or slug the order belongs to (its deployment's autonomy level applies)"
    },
    "rationale" : {
      "type" : "string",
      "maxLength" : 500,
      "description" : "Why, in one or two sentences, shown to the approver"
    }
  },
  "additionalProperties" : false
}
```

Output schema:

```json
{
  "type" : "object",
  "properties" : {
    "approvalId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "kind" : {
      "type" : "string"
    },
    "status" : {
      "type" : "string"
    },
    "summary" : {
      "type" : "string"
    },
    "intentId" : {
      "type" : "string",
      "format" : "uuid"
    },
    "expiresAt" : {
      "type" : "string"
    },
    "policyDecision" : {
      "type" : "string"
    },
    "policyReason" : {
      "type" : "string"
    },
    "message" : {
      "type" : "string"
    }
  }
}
```
