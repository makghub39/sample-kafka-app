package com.example.repository;

import com.example.model.CustomerData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderRepositoryIntegrationTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private OrderRepository repo;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.jdbcTemplate = new NamedParameterJdbcTemplate(ds);

        // Ensure a clean database state for each test run
        jdbcTemplate.getJdbcTemplate().execute("DROP ALL OBJECTS");

        // Run schema.sql
        try (InputStream in = getClass().getResourceAsStream("/schema.sql"); java.util.Scanner s = new java.util.Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            String sql = s.hasNext() ? s.next() : "";
            for (String stmt : sql.split(";")) {
                String t = stmt.trim();
                if (!t.isEmpty()) {
                    jdbcTemplate.getJdbcTemplate().execute(t);
                }
            }
        }

        // Insert sample data
        jdbcTemplate.getJdbcTemplate().update("INSERT INTO customers(customer_id,name,email,tier) VALUES('cust1','Name','e@e','standard')");
        jdbcTemplate.getJdbcTemplate().update("INSERT INTO orders(order_id,customer_id,status,amount,created_at) VALUES('o1','cust1','NEW',10.0,NOW())");

        SqlTemplateLoader loader = new SqlTemplateLoader(new org.springframework.core.io.DefaultResourceLoader());
        this.repo = new OrderRepository(jdbcTemplate, 500, 1, 1L, loader);
    }

    @Test
    void findOrdersByIds_shouldReturnInsertedOrder() {
        List<com.example.model.Order> orders = repo.findOrdersByIds(List.of("o1"));
        assertEquals(1, orders.size());
        assertEquals("o1", orders.get(0).id());
    }

    @Test
    void batchFetchCustomerData_shouldReturnCustomerForOrder() {
        Map<String, CustomerData> map = repo.batchFetchCustomerData(List.of("o1"));
        assertEquals(1, map.size());
        assertTrue(map.containsKey("o1"));
        assertEquals("cust1", map.get("o1").customerId());
    }
}
