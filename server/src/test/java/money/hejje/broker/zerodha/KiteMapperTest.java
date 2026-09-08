package money.hejje.broker.zerodha;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.DataException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.GeneralException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.InputException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.NetworkException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.OrderException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.PermissionException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.TokenException;
import com.zerodhatech.models.OrderParams;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;
import org.junit.jupiter.api.Test;

class KiteMapperTest {

    @Test
    void mapsEveryKiteErrorFamily() {
        assertThat(KiteMapper.toBrokerException(new TokenException("expired", 403)).kind()).isEqualTo(BrokerException.Kind.AUTH);
        assertThat(KiteMapper.toBrokerException(new PermissionException("no", 403)).kind()).isEqualTo(BrokerException.Kind.AUTH);
        assertThat(KiteMapper.toBrokerException(new NetworkException("Too many requests", 429)).kind()).isEqualTo(BrokerException.Kind.RATE_LIMIT);
        assertThat(KiteMapper.toBrokerException(new NetworkException("gateway", 502)).kind()).isEqualTo(BrokerException.Kind.NETWORK);
        assertThat(KiteMapper.toBrokerException(new InputException("bad qty", 400)).kind()).isEqualTo(BrokerException.Kind.INPUT);
        assertThat(KiteMapper.toBrokerException(new OrderException("insufficient funds", 400)).kind()).isEqualTo(BrokerException.Kind.REJECTED);
        assertThat(KiteMapper.toBrokerException(new GeneralException("oops", 500)).kind()).isEqualTo(BrokerException.Kind.NETWORK);
        assertThat(KiteMapper.toBrokerException(new DataException("no data", 400)).kind()).isEqualTo(BrokerException.Kind.UNKNOWN);
        assertThat(KiteMapper.toBrokerException(new SocketTimeoutException("read timed out")).kind()).isEqualTo(BrokerException.Kind.TIMEOUT);
        assertThat(KiteMapper.toBrokerException(new IOException("reset")).kind()).isEqualTo(BrokerException.Kind.NETWORK);
        assertThat(KiteMapper.toBrokerException(new SocketTimeoutException("x")).outcomeUnknown()).isTrue();
        assertThat(KiteMapper.toBrokerException(new OrderException("x", 400)).outcomeUnknown()).isFalse();
    }

    @Test
    void mapsStatusStrings() {
        assertThat(KiteMapper.status("COMPLETE")).isEqualTo(BrokerOrderStatus.COMPLETE);
        assertThat(KiteMapper.status("OPEN")).isEqualTo(BrokerOrderStatus.OPEN);
        assertThat(KiteMapper.status("TRIGGER PENDING")).isEqualTo(BrokerOrderStatus.TRIGGER_PENDING);
        assertThat(KiteMapper.status("PUT ORDER REQ RECEIVED")).isEqualTo(BrokerOrderStatus.PENDING);
        assertThat(KiteMapper.status("VALIDATION PENDING")).isEqualTo(BrokerOrderStatus.PENDING);
        assertThat(KiteMapper.status("MODIFY PENDING")).isEqualTo(BrokerOrderStatus.MODIFY_PENDING);
        assertThat(KiteMapper.status("CANCEL PENDING")).isEqualTo(BrokerOrderStatus.CANCEL_PENDING);
        assertThat(KiteMapper.status("CANCELLED")).isEqualTo(BrokerOrderStatus.CANCELLED);
        assertThat(KiteMapper.status("REJECTED")).isEqualTo(BrokerOrderStatus.REJECTED);
        assertThat(KiteMapper.status("something new")).isEqualTo(BrokerOrderStatus.UNKNOWN);
        assertThat(KiteMapper.status(null)).isEqualTo(BrokerOrderStatus.UNKNOWN);
    }

    @Test
    void mapsOrderParams() {
        UUID id = UUID.randomUUID();
        BrokerInstrumentRef ref = new BrokerInstrumentRef(id, Exchange.NFO, InstrumentType.FUT, "13368834", "NIFTY26SEPFUT", "NFO", 75, new BigDecimal("0.05"));
        OrderParams sl = KiteMapper.orderParams(new BrokerOrderRequest(id, Side.SELL, Quantity.of(75), OrderType.SL, Product.NRML,
                Price.of("24900.00"), Price.of("24905.00"), Validity.DAY, "hj1"), ref);
        assertThat(sl.exchange).isEqualTo("NFO");
        assertThat(sl.tradingsymbol).isEqualTo("NIFTY26SEPFUT");
        assertThat(sl.transactionType).isEqualTo("SELL");
        assertThat(sl.orderType).isEqualTo("SL");
        assertThat(sl.product).isEqualTo("NRML");
        assertThat(sl.price).isEqualTo(24900.0);
        assertThat(sl.triggerPrice).isEqualTo(24905.0);
        assertThat(sl.validity).isEqualTo("DAY");
        assertThat(sl.tag).isEqualTo("hj1");

        OrderParams market = KiteMapper.orderParams(new BrokerOrderRequest(id, Side.BUY, Quantity.of(75), OrderType.MARKET, Product.MIS,
                null, null, Validity.IOC, null), ref);
        assertThat(market.orderType).isEqualTo("MARKET");
        assertThat(market.price).isNull();
        assertThat(market.triggerPrice).isNull();
        assertThat(market.validity).isEqualTo("IOC");

        OrderParams slm = KiteMapper.orderParams(new BrokerOrderRequest(id, Side.BUY, Quantity.of(75), OrderType.SL_M, Product.MIS,
                null, Price.of("25000"), null, null), ref);
        assertThat(slm.orderType).isEqualTo("SL-M");
    }

    @Test
    void convertsKiteWallClockTimes() {
        // The library parses "2026-09-08 09:15:00" in the JVM default zone; the mapper must read it as IST.
        java.util.Date parsed = java.util.Date.from(java.time.LocalDateTime.of(2026, 9, 8, 9, 15).atZone(java.time.ZoneId.systemDefault()).toInstant());
        assertThat(KiteMapper.instant(parsed)).isEqualTo(Instant.parse("2026-09-08T03:45:00Z"));
        assertThat(KiteMapper.instant(KiteMapper.kiteDate(Instant.parse("2026-09-08T03:45:00Z")))).isEqualTo(Instant.parse("2026-09-08T03:45:00Z"));
        assertThat(KiteMapper.candleInstant("2026-09-08T09:15:00+0530")).isEqualTo(Instant.parse("2026-09-08T03:45:00Z"));
    }
}
