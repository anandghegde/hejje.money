package money.hejje.auth;

import money.hejje.auth.internal.BearerAuthenticationFilter;
import money.hejje.auth.internal.ProblemAuthHandlers;
import money.hejje.auth.internal.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless bearer-token security. JWTs (users) and {@code hejje_...} API keys (clients) are both accepted in
 * {@code Authorization: Bearer}. Scopes become {@code SCOPE_*} authorities checked with {@code @PreAuthorize}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BearerAuthenticationFilter bearer, RateLimitFilter rateLimit,
            ProblemAuthHandlers handlers) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/server/ping").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/broker/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/broker/postback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/*").permitAll() // authenticated by the webhook signature (M5.5)
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimit, BearerAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}
