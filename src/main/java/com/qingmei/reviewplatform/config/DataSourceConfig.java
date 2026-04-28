package com.qingmei.reviewplatform.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;

@Configuration
public class DataSourceConfig {

    @Value("${POSTGRES_DSN:postgres://postgres:postgres@localhost:5432/review_platform?sslmode=disable}")
    private String postgresDsn;

    @Bean
    public DataSource dataSource() {
        try {
            URI uri = new URI(postgresDsn);
            String[] userInfo = uri.getUserInfo().split(":", 2);
            String username = userInfo[0];
            String password = userInfo.length > 1 ? userInfo[1] : "";
            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
            if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
                jdbcUrl = jdbcUrl + "?" + uri.getQuery();
            }

            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(jdbcUrl);
            ds.setUsername(username);
            ds.setPassword(password);
            ds.setMaximumPoolSize(10);
            return ds;
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("invalid POSTGRES_DSN", ex);
        }
    }
}
