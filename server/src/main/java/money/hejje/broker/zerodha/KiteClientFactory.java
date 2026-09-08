package money.hejje.broker.zerodha;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.Routes;
import com.zerodhatech.kiteconnect.kitehttp.KiteRequestHandler;
import java.lang.reflect.Field;
import okhttp3.OkHttpClient;

/**
 * Builds {@link KiteConnect} instances. javakiteconnect hard-codes its base URL and HTTP timeouts, so the two are
 * applied reflectively (a static field on {@code Routes} and the private OkHttp client of the request handler).
 * This is the only place the library's internals are touched.
 */
final class KiteClientFactory {

    static final String DEFAULT_BASE_URL = "https://api.kite.trade";

    private KiteClientFactory() {
    }

    static KiteConnect create(KiteProperties properties) {
        applyBaseUrl(properties.baseUrl());
        KiteConnect kite = new KiteConnect(properties.apiKey(), null, false);
        KiteRequestHandler handler = new KiteRequestHandler(null);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(properties.connectTimeout())
                .readTimeout(properties.readTimeout())
                .writeTimeout(properties.readTimeout())
                .build();
        set(handler, KiteRequestHandler.class, "client", client);
        set(kite, KiteConnect.class, "kiteRequestHandler", handler);
        return kite;
    }

    static void applyBaseUrl(String baseUrl) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
        try {
            Field field = Routes.class.getDeclaredField("_rootUrl");
            field.setAccessible(true);
            field.set(null, url);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot configure Kite base URL (library changed?)", e);
        }
    }

    private static void set(Object target, Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot configure Kite client field " + name + " (library changed?)", e);
        }
    }
}
