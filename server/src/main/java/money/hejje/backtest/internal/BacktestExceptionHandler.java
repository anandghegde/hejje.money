package money.hejje.backtest.internal;

import money.hejje.backtest.BacktestException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class BacktestExceptionHandler {

    @ExceptionHandler(BacktestException.class)
    ProblemDetail backtest(BacktestException ex) {
        HttpStatus status = ex.getMessage() != null && ex.getMessage().endsWith("not found") ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        return ProblemDetail.forStatusAndDetail(status, ex.getMessage());
    }
}
