package com.eventguard.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/** 投影连接池与命令/查询池隔离，总连接预算由配置共同限制。 */
@Configuration
public class ProjectionDataSourceConfig {

    @Bean
    @ConfigurationProperties("eventguard.projection-datasource")
    DataSourceProperties projectionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("eventguard.projection-datasource.hikari")
    HikariDataSource projectionDataSource(
            @Qualifier("projectionDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    JdbcTemplate projectionJdbcTemplate(@Qualifier("projectionDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    DataSourceTransactionManager projectionTransactionManager(
            @Qualifier("projectionDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
