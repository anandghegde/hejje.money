package money.hejje.strategy.internal;

import java.net.URI;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps strategy module failures to RFC 7807 problems ahead of the common catch-all advice. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class StrategyExceptionHandler {

    @ExceptionHandler(StrategyValidationException.class)
    ProblemDetail invalid(StrategyValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid strategy definition");
        problem.setType(URI.create("https://hejje.money/problems/strategy-validation"));
        problem.setTitle("Invalid strategy definition");
        problem.setProperty("errors", ex.errors());
        return problem;
    }

    @ExceptionHandler(StrategyException.NotFound.class)
    ProblemDetail notFound(StrategyException.NotFound ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(StrategyException.Conflict.class)
    ProblemDetail conflict(StrategyException.Conflict ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
}
