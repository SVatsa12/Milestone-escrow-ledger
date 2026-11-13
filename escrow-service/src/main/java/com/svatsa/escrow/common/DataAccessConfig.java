package com.svatsa.escrow.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Configuration for data access.
 * 
 * Spring automatically creates a JdbcClient bean if a DataSource is available.
 * This config just documents it.
 */
@Configuration
public class DataAccessConfig {
}
