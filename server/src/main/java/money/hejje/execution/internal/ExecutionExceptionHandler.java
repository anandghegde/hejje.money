package money.hejje.execution.internal;

import java.net.URI;
import money.hejje.execution.ExecutionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps execution pipeline rejections to RFC 7807 problems. Ordered ahead of the common catch-all advice, which would
 * otherwise turn these into a 500 "Unexpected error".
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ExecutionExceptionHandler {

    @ExceptionHandler(ExecutionException.Validation.class)
    ProblemDetail validation(ExecutionException.Validation ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Order validation failed");
        problem.setType(URI.create("https://hejje.money/problems/order-validation"));
        problem.setTitle("Order validation failed");
        problem.setProperty("reasons", ex.reasons());
        return problem;
    }

    @ExceptionHandler(ExecutionException.RiskRejected.class)
    ProblemDetail risk(ExecutionException.RiskRejected ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Risk rejected the order");
        problem.setType(URI.create("https://hejje.money/problems/risk-rejected"));
        problem.setTitle("Risk rejected");
        problem.setProperty("checks", ex.checks());
        return problem;
    }

    @ExceptionHandler(ExecutionException.NotActiveExecutor.class)
    ProblemDetail standby(ExecutionException.NotActiveExecutor ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setTitle("Not the active executor");
        return problem;
    }

    @ExceptionHandler(ExecutionException.IdempotencyInFlight.class)
    ProblemDetail inFlight(ExecutionException.IdempotencyInFlight ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ExecutionException.IdempotencyMismatch.class)
    ProblemDetail mismatch(ExecutionException.IdempotencyMismatch ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }
}
