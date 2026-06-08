package com.fintech.api.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Tenta conectar à branch develop do Neon no startup.
 * Lê application-local.properties diretamente do classpath — sem depender
 * da ordem de carregamento do Spring — para garantir acesso às credenciais.
 * Se Neon acessível: sobrescreve spring.datasource.* com credenciais da Neon.
 * Se inacessível: mantém banco local (Docker Compose) como fallback.
 */
public class NeonFallbackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final int NEON_PORT    = 5432;
    private static final int TIMEOUT_MS   = 2000;
    private static final int JDBC_TIMEOUT = 3;   // segundos — cobre TCP + SSL + auth

    @Override
    public int getOrder() {
        // Roda cedo o suficiente para sobrescrever datasource antes de qualquer bean ser criado
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Properties localProps = loadLocalProperties();
        if (localProps == null) {
            return;
        }

        String neonUrl = localProps.getProperty("neon.datasource.url");
        if (neonUrl == null) {
            return;
        }

        String host     = extractHost(neonUrl);
        String username = localProps.getProperty("neon.datasource.username");
        String password = localProps.getProperty("neon.datasource.password");

        if (!isReachable(host, NEON_PORT, TIMEOUT_MS)) {
            System.out.println(">>> [NeonFallback] Neon inacessível (TCP) — usando banco local (Docker Compose)");
            return;
        }

        // TCP abriu, mas redes com restrição de SSL podem bloquear o handshake.
        // Testa a conexão JDBC completa (TCP + SSL + autenticação) antes de confirmar o Neon.
        if (!canConnectViaJdbc(neonUrl, username, password)) {
            System.out.println(">>> [NeonFallback] Neon TCP ok mas SSL/JDBC bloqueado — usando banco local (Docker Compose)");
            return;
        }

        Map<String, Object> neonProps = new LinkedHashMap<>();
        neonProps.put("spring.datasource.url", neonUrl);
        neonProps.put("spring.datasource.username", username);
        neonProps.put("spring.datasource.password", password);
        // Neon é serverless: conexões podem cair quando idle. Esses valores evitam timeouts silenciosos.
        neonProps.put("spring.datasource.hikari.max-lifetime", 600000);
        neonProps.put("spring.datasource.hikari.keepalive-time", 300000);

        environment.getPropertySources().addFirst(new MapPropertySource("neon-datasource", neonProps));

        System.out.println(">>> [NeonFallback] Neon acessível — usando branch develop da Neon");
    }

    private Properties loadLocalProperties() {
        ClassPathResource resource = new ClassPathResource("application-local.properties");
        if (!resource.exists()) {
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            Properties props = new Properties();
            props.load(is);
            return props;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean canConnectViaJdbc(String url, String username, String password) {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        // loginTimeout cobre todo o handshake: TCP + SSL + autenticação Postgres
        props.setProperty("loginTimeout", String.valueOf(JDBC_TIMEOUT));
        props.setProperty("socketTimeout", String.valueOf(JDBC_TIMEOUT));
        props.setProperty("connectTimeout", String.valueOf(JDBC_TIMEOUT));
        try (Connection ignored = DriverManager.getConnection(url, props)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Extrai o host de uma URL JDBC: jdbc:postgresql://host:port/db?params
    private String extractHost(String jdbcUrl) {
        String withoutScheme = jdbcUrl.replace("jdbc:postgresql://", "");
        String hostAndPort = withoutScheme.split("/")[0];
        return hostAndPort.split(":")[0];
    }
}
