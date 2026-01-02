package com.example.repository;

import com.example.model.CustomerData;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderRepositoryRetryTest {

    @Test
    void withRetry_transientFailure_thenSuccess() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SqlTemplateLoader sqlLoader = mock(SqlTemplateLoader.class);
        when(sqlLoader.load("batchFetchCustomerData")).thenReturn("SELECT ...");

        // first call throws, second returns results
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenThrow(new DataAccessException("transient") {})
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) params.getValue("orderIds");
                    Map<String, CustomerData> result = new HashMap<>();
                    for (String id : ids) result.put(id, new CustomerData("c-" + id, "n", "e", "t"));
                    return result;
                });

        OrderRepository repo = new OrderRepository(jdbc, 5, 1, 1L, sqlLoader);

        Map<String, CustomerData> res = repo.batchFetchCustomerData(List.of("a", "b"));
        assertEquals(2, res.size());
    }

    @Test
    void withRetry_exhaustedRetries_shouldThrow() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SqlTemplateLoader sqlLoader = mock(SqlTemplateLoader.class);
        when(sqlLoader.load("batchFetchCustomerData")).thenReturn("SELECT ...");

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class)))
                .thenThrow(new DataAccessException("perm") {});

        OrderRepository repo = new OrderRepository(jdbc, 5, 0, 1L, sqlLoader);

        assertThrows(DataAccessException.class, () -> repo.batchFetchCustomerData(List.of("x")));
    }
}
