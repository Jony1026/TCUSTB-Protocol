package net.tdteam.ustb.library.application;

import java.util.Map;
import net.tdteam.ustb.common.error.ProtocolException;
import net.tdteam.ustb.library.infrastructure.LibraryCatalogClient;
import net.tdteam.ustb.library.model.CatalogPage;
import net.tdteam.ustb.library.model.HoldingsPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 馆藏业务服务：校验工具箱会话、搜索字段白名单及分页边界。@author itsjony01 @date 2026-10-01 */
@Service
public class LibraryCatalogService {
    private static final Map<String, String> FIELDS = Map.of(
            "keyword", "keyWord", "title", "title", "author", "author", "isbn", "isbn");
    private final LibraryCatalogClient client;

    public LibraryCatalogService(LibraryCatalogClient client) {
        this.client = client;
    }

    public CatalogPage search(String keyword, String field, int page, int pageSize) {
        validatePage(page, pageSize);
        if (keyword == null || keyword.isBlank() || keyword.strip().length() > 100
                || keyword.codePoints().anyMatch(Character::isISOControl)
                || field == null || !FIELDS.containsKey(field)) throw invalid();
        return client.search(keyword.strip(), FIELDS.get(field), page, pageSize);
    }

    public HoldingsPage holdings(String id, int page, int pageSize) {
        validatePage(page, pageSize);
        if (id == null || !id.matches("[1-9][0-9]{0,17}")) throw invalid();
        return client.holdings(id, page, pageSize);
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || page > 10000 || pageSize < 1 || pageSize > 20) throw invalid();
    }

    private static ProtocolException invalid() {
        return new ProtocolException(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_QUERY", "Invalid library query");
    }
}
