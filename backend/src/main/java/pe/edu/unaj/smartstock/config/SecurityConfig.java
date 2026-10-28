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

/**
 * Dos cadenas de seguridad:
 *  1) /api/iot/**  -> dispositivos ESP32, sin sesion, autenticados por cabeceras (sin cookies: no aplica CSRF).
 *  2) resto        -> usuarios con sesion (cookie HttpOnly + SameSite=Lax), roles ADMIN/OPERADOR.
 * CSRF activo en la cadena web: token en la cookie XSRF-TOKEN que el frontend devuelve
 * en la cabecera X-XSRF-TOKEN (ver js/api.js). SameSite=Lax por si solo no basta:
 * otra aplicacion en el mismo sitio (otro puerto de localhost, un subdominio) si envia la cookie.
 */
@Configuration
public class SecurityConfig {

    /** Politica de contenido: solo scripts propios (Chart.js se sirve localmente); sin scripts en linea. */
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
                // Frontend estatico (cada pagina verifica la sesion con /api/auth/me)
                .requestMatchers("/", "/*.html", "/css/**", "/js/**", "/img/**", "/favicon.ico", "/error").permitAll()
                // Estado del servidor (sin datos sensibles): Docker y el instalador lo consultan
                .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                // Solo ADMIN: configuracion, catalogo (escritura), revision y reportes
                .requestMatchers(HttpMethod.POST, "/api/productos/**", "/api/categorias/**", "/api/ubicaciones/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/productos/**", "/api/categorias/**", "/api/ubicaciones/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/productos/**").hasRole("ADMIN")
                .requestMatchers("/api/dispositivos/**", "/api/sensores/**", "/api/calibraciones/**").hasRole("ADMIN")
                .requestMatchers("/api/discrepancias/*/aprobar", "/api/discrepancias/*/rechazar").hasRole("ADMIN")
                .requestMatchers("/api/auditoria/**", "/api/reportes/**").hasRole("ADMIN")
                .requestMatchers("/api/ventas/*/anular").hasRole("ADMIN")
                // Resto de la API: cualquier usuario autenticado (ADMIN u OPERADOR)
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
