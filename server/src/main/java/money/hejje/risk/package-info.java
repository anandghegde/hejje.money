/**
 * Risk module: deterministic pre-trade controls and the kill switch (PRD sections 31, 32). M1.4 introduces the public
 * {@link money.hejje.risk.RiskEngine} port with an approve-all stub; the real engine, limits and kill switch arrive in M1.5.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.risk;
