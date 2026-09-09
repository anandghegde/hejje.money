/**
 * Strategy condition DSL (PRD section 10, docs/strategy-dsl.md): tokenizer, recursive-descent parser, AST, indicator
 * catalogue and the evaluator. Shared by the validator, the backtester and the live signal engine so that both evaluate
 * exactly the same code (README rule 10).
 */
@org.springframework.modulith.NamedInterface("dsl")
package money.hejje.strategy.dsl;
