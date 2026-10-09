package net.tdteam.ustb.library.model;

import java.util.List;

/** 馆藏书目分页结果，缺失字段不伪造数据。@author itsjony01 @date 2026-10-01 */
public record CatalogPage(long total, int page, int pageSize, List<Book> items) {
    public record Book(String id, String title, String author, String publisher, String publishYear,
                       String isbn, String callNumber, String summary, Long copies) { }
}
