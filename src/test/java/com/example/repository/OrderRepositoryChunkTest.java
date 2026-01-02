package com.example.repository;

import com.example.model.CustomerData;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import com.example.repository.SqlTemplateLoader;
import org.mockito.ArgumentMatchers;
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

/**
 * Unit test verifying repository chunking behavior when one chunk fails.
 */
class OrderRepositoryChunkTest {

    @Test
    void batchFetchCustomerData_oneChunkFails_shouldContinueAndReturnPartialResults() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

        // Use a small chunk size so the input list will be partitioned
        int chunkSize = 2;
        SqlTemplateLoader sqlLoader = mock(SqlTemplateLoader.class);
        when(sqlLoader.load("batchFetchCustomerData")).thenReturn("SELECT ...");
        OrderRepository repo = new OrderRepository(jdbc, chunkSize, 0, 1L, sqlLoader);

        // Mock jdbc.query to return results for normal chunks and throw for a chunk containing "bad"
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Map<String, CustomerData>>>any()))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) params.getValue("orderIds");

                    if (ids.contains("bad")) {
                        throw new DataAccessException("simulated chunk failure") {};
                    }

                    Map<String, CustomerData> result = new HashMap<>();
                    for (String id : ids) {
                        result.put(id, new CustomerData("cust-" + id, "Name-" + id, id + "@example.com", "standard"));
                    }
                    return result;
                });

        List<String> orderIds = List.of("id1", "id2", "bad", "id3", "id4");

        Map<String, CustomerData> res = repo.batchFetchCustomerData(orderIds);

        // With chunkSize=2 partitions are: [id1,id2], [bad,id3], [id4]
        // Middle chunk should fail and be skipped by the repository; other chunks succeed
        assertEquals(3, res.size());
        assertTrue(res.containsKey("id1"));
        assertTrue(res.containsKey("id2"));
        assertTrue(res.containsKey("id4"));
        assertFalse(res.containsKey("bad"));
        assertFalse(res.containsKey("id3"));
    }

    @Test
    void batchFetchCustomerData_allChunksSucceed_shouldMergeAllResults() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

        int chunkSize = 2;
        SqlTemplateLoader sqlLoader = mock(SqlTemplateLoader.class);
        when(sqlLoader.load("batchFetchCustomerData")).thenReturn("SELECT ...");
        OrderRepository repo = new OrderRepository(jdbc, chunkSize, 0, 1L, sqlLoader);

        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Map<String, CustomerData>>>any()))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) params.getValue("orderIds");

                    Map<String, CustomerData> result = new HashMap<>();
                    for (String id : ids) {
                        result.put(id, new CustomerData("cust-" + id, "Name-" + id, id + "@example.com", "standard"));
                    }
                    return result;
                });

        List<String> orderIds = List.of("id1", "id2", "id3", "id4");

        Map<String, CustomerData> res = repo.batchFetchCustomerData(orderIds);

        assertEquals(4, res.size());
        assertTrue(res.containsKey("id1"));
        assertTrue(res.containsKey("id2"));
        assertTrue(res.containsKey("id3"));
        assertTrue(res.containsKey("id4"));
    }
}
