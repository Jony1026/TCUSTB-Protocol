package net.tdteam.ustb.library.api;

import net.tdteam.ustb.library.application.LibraryCatalogService;
import net.tdteam.ustb.library.model.CatalogPage;
import net.tdteam.ustb.library.model.HoldingsPage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 超星学习通接口参考来自贝壳小盒子作者。感谢~
 * @author itsjony01
 * @date 2026-10-01
 **/
@RestController
@RequestMapping("/api/v1/library/books")
public class LibraryCatalogController {
    private final LibraryCatalogService service;

    public LibraryCatalogController(LibraryCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<CatalogPage> search(
            @RequestParam String keyword, @RequestParam(defaultValue = "keyword") String field,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.search(keyword, field, page, pageSize));
    }

    @GetMapping("/{id}/holdings")
    public ResponseEntity<HoldingsPage> holdings(
            @PathVariable String id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.holdings(id, page, pageSize));
    }
}
