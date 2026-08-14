package com.auditlog.api.dto;

import com.auditlog.domain.PageResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

@Schema(name = "PagedResponse", description = "A page of results ordered by chain sequence")
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <S, T> PagedResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        return new PagedResponse<>(
                result.content().stream().map(mapper).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());
    }
}
