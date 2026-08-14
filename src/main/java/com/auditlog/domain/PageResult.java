package com.auditlog.domain;

import java.util.List;

/** A page of results, kept framework-free so the domain layer does not depend on Spring Data. */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResult<>(List.copyOf(content), page, size, totalElements, totalPages);
    }
}
