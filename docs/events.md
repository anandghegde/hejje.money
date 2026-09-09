# Events

Hejje uses two in-process event channels. Both live in `money.hejje.common.event`.

## 1. Durable domain events

Used for anything that changes trading state: orders, signals, positions, risk decisions, readiness changes.

- Events are immutable records implementing `HejjeEvent` (`id`, `occurredAt`, `correlationId`). Embed an
  `EventMeta` component created with `EventMeta.create(clock)` to satisfy the contract.
- Publish with Spring's `ApplicationEventPublisher` from inside the transaction that made the change.
- Consume with `@ApplicationModuleListener` (Spring Modulith). The listener runs asynchronously after the
  publishing transaction commits, in its own transaction.
- Spring Modulith's event publication registry persists every publication in `event_publication`
  (created by `V1__baseline.sql`). Incomplete publications are re-delivered on restart
  (`spring.modulith.events.republish-outstanding-events-on-restart=true`), so listeners must be idempotent.
- Events cross module boundaries; they are the only way for one module to react to another without a
  direct dependency on its API.

## 2. High-volume market events

Used for ticks and candles, thousands per second during the session.

- Payloads implement `MarketEvent` (`occurredAt`).
- Delivered through `TickBus` (`publish`, `subscribe`), a plain in-memory listener registry with no
  persistence and no transactions. Listeners run on the publisher's thread and must be fast and non-blocking.
- The implementation arrives with the market data module in M1.3. Modules code against the interface now.

## Correlation

Every HTTP request carries `X-Correlation-Id` (a UUID; generated when absent or invalid, echoed back on
the response). It is bound to the logging MDC as `correlationId`, appears on every log line, defaults into
every audit event and should be copied into every domain event via `EventMeta.create(...)`.

## Event catalogue (Phase 1)

| Event | Module | When |
|---|---|---|
| `EgressIpStatusChanged` | system | egress IP verification result changes |
| `BrokerSessionChanged(broker, previous, current, detail)` | broker | login, logout, expiry, broker-side rejection |

## Broker order updates

`BrokerOrderUpdate`s (order state changes from the broker WebSocket, postback, poll or simulation) are not domain events:
they are fanned out in-process through `money.hejje.broker.BrokerOrderUpdates` (`subscribe`, `publish`) on the producing
thread. The execution module turns them into durable `OrderStateChangedEvent`s after applying them to the order state machine.

## Market events (Phase 1, M1.3)

`MarketTick` and `CandleClosedEvent` are `MarketEvent`s delivered on the in-process `TickBus`
(`money.hejje.market.internal.InProcessTickBus`): a bounded queue with one dispatcher thread that guarantees total
ordering; on overflow the oldest event is dropped and `hejje_tick_bus_dropped_total` is incremented. The client market
WebSocket and the candle builder subscribe here. These are never persisted.

## Order and position events (Phase 1, M1.4)

Durable domain events published inside the execution transaction and consumed with `@ApplicationModuleListener`:
`OrderIntentCreatedEvent`, `OrderSubmittedEvent`, `OrderStateChangedEvent`, `OrderFilledEvent`, `PositionChangedEvent`.
Broker order updates (WebSocket, postback, poll, simulation) arrive on the in-process `BrokerOrderUpdates` bus and are
turned into these durable events by `OrderService.applyBrokerUpdate` after the state machine runs. Order transitions and
fills are serialized per order id, so updates racing ahead of the local acknowledgement never corrupt state.

## Strategy events (Phase 2, M2.1)

`StrategyVersionStatusChanged(strategyId, versionId, from, to)` and `DeploymentChanged(deploymentId, versionId, enabled)`
are durable events from the strategy module. The signal engine (M2.6) starts and stops runners on `DeploymentChanged`.
