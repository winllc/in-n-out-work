package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** One page of a list: its items, and where it stands. Pages count from 1. */
@Schema(description = "One page of results. Pages count from 1.")
public record ApiPage<T>(
        @Schema(description = "This page's items") List<T> items,
        @Schema(description = "This page, from 1", example = "1") int page,
        @Schema(description = "The most items a page holds", example = "100") int size,
        @Schema(description = "Items across every page", example = "1234") long totalItems,
        @Schema(description = "Pages in all", example = "13") int totalPages) {

    public static <E, T> ApiPage<T> of(Page<E> page, Function<E, T> map) {
        return new ApiPage<>(page.getContent().stream().map(map).toList(), page.getNumber() + 1, page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> ApiPage<T> of(Page<?> page, List<T> items) {
        return new ApiPage<>(items, page.getNumber() + 1, page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
