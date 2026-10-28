package pe.edu.unaj.smartstock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import pe.edu.unaj.smartstock.repository.DispositivoRepository;
import pe.edu.unaj.smartstock.security.DispositivoAuthFilter;
import pe.edu.unaj.smartstock.security.IntentosFallidos;
import pe.edu.unaj.smartstock.security.LoginLimiteFilter;
import pe.edu.unaj.smartstock.security.SpaCsrfTokenRequestHandler;

import static pe.edu.unaj.smartstock.security.RespuestaJson.escribir;

@Configuration
public class SecurityConfig {

    static final String CSP = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain iotChain(HttpSecurity http, DispositivoRepository dispositivos,
                                        PasswordEncoder encoder, IntentosFallidos intentos) throws Exception {
        http.securityMatcher("/api/iot/**")
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new DispositivoAuthFilter(dispositivos, encoder, intentos), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("DEVICE"))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> escribir(res, 401, "Dispositivo no autenticado"))
                .accessDeniedHandler((req, res, ex) -> escribir(res, 403, "Acceso denegado")));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain webChain(HttpSecurity http, IntentosFallidos intentos) throws Exception {
        http.csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterBefore(new LoginLimiteFilter(intentos), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/", "/*.html", "/css/**", "/js/**", "/img/**", "/favicon.ico", "/error").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/productos/**", "/api/categorias/**", "/api/ubicaciones/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/productos/**", "/api/categorias/**", "/api/ubicaciones/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/productos/**").hasRole("ADMIN")
                .requestMatchers("/api/dispositivos/**", "/api/sensores/**", "/api/calibraciones/**").hasRole("ADMIN")
                .requestMatchers("/api/discrepancias/*/aprobar", "/api/discrepancias/*/rechazar").hasRole("ADMIN")
                .requestMatchers("/api/auditoria/**", "/api/reportes/**").hasRole("ADMIN")
                .requestMatchers("/api/ventas/*/anular").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .formLogin(f -> f
                .loginProcessingUrl(LoginLimiteFilter.URL_LOGIN)
                .successHandler((req, res, auth) -> {
                    intentos.exito(LoginLimiteFilter.clave(req, auth.getName()));
                    escribir(res, 200, "Sesion iniciada");
                })
                .failureHandler((req, res, ex) -> {
                    if (ex instanceof BadCredentialsException) {
                        intentos.fallo(LoginLimiteFilter.clave(req, req.getParameter("username")));
                    }
                    escribir(res, 401, "Usuario o contrasena incorrectos");
                }))
            .logout(l -> l
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> escribir(res, 200, "Sesion cerrada")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> escribir(res, 401, "No autenticado"))
                .accessDeniedHandler((req, res, ex) -> escribir(res, 403, ex instanceof CsrfException
                        ? "Token CSRF ausente o invalido. Recargue la pagina"
                        : "No tiene permisos para esta operacion")))
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentSecurityPolicy(c -> c.policyDirectives(CSP)));
        return http.build();
    }
}
