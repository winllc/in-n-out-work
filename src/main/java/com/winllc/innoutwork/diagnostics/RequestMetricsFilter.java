package com.winllc.innoutwork.diagnostics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Logs, for each page and API call, how many SQL statements and LDAP operations it took and how long. */
public class RequestMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsFilter.class);

    /** Static files cause no queries worth reporting. */
    private static final List<String> SKIPPED_PREFIXES = List.of("/css/", "/js/", "/img/", "/images/", "/webjars/", "/favicon");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return SKIPPED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        RequestMetrics.start();
        try {
            chain.doFilter(request, response);
        } finally {
            RequestMetrics.Counts counts = RequestMetrics.stop();
            log.info("{} {} -> {}: {} SQL statement(s), {} LDAP operation(s), {}ms",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    counts.sqlStatements(), counts.ldapOperations(), (System.nanoTime() - start) / 1_000_000);
        }
    }
}
