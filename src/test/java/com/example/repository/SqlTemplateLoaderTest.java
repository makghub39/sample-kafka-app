package com.example.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

class SqlTemplateLoaderTest {

    @Test
    void load_namedQuery_shouldReturnQueryText() {
        SqlTemplateLoader loader = new SqlTemplateLoader(new DefaultResourceLoader());

        String sql = loader.load("batchFetchCustomerData");
        assertNotNull(sql);
        assertTrue(sql.toLowerCase().contains("from customers"));
    }

    @Test
    void load_missingName_shouldThrow() {
        SqlTemplateLoader loader = new SqlTemplateLoader(new DefaultResourceLoader());
        assertThrows(IllegalArgumentException.class, () -> loader.load("no_such_query"));
    }
}
