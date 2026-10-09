package net.tdteam.ustb.library.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.tdteam.ustb.common.error.ProtocolException;
import net.tdteam.ustb.library.model.CatalogPage;
import net.tdteam.ustb.library.model.HoldingsPage;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 学院公开馆藏协议客户端；仅使用公开检索参数，不传递教务凭据。@author itsjony01 @date 2026-10-01 */
@Component
public class LibraryCatalogClient {
    private final URI origin;
    private final Duration timeout;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LibraryCatalogClient() {
        // 图书馆检索高峰期会出现短暂排队，留足上游预算但不无限等待。@author itsjony01 @date 2026-10-01
        this(URI.create("https://findtjustb.libsp.cn"), Duration.ofSeconds(30));
    }

    LibraryCatalogClient(URI origin, Duration timeout) {
        this.origin = origin;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 固定实体图书范围，避免把电子资源或其他文献误当成本馆图书。@author itsjony01 @date 2026-10-01 */
    public CatalogPage search(String keyword, String field, int page, int pageSize) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("searchFieldContent", keyword);
        body.put("searchField", field);
        body.put("page", page);
        body.put("rows", pageSize);
        body.put("matchMode", "2");
        body.put("resourceType", List.of("1"));
        body.put("docCode", List.of("1"));
        body.put("libCode", List.of("20096000001"));
        body.put("sortField", "relevance");
        body.put("sortClause", "asc");
        body.put("indexSearch", 1);
        JsonNode data = post("/find/unify/search", body);
        long total = total(data, "numFound", "searchResult");
        List<CatalogPage.Book> books = new ArrayList<>();
        for (JsonNode book : data.path("searchResult")) {
            String id = identifier(book, "recordId");
            String title = text(book, "title");
            if (title.isBlank()) throw formatError();
            String callNumber = text(book, "callNoOne");
            if (callNumber.isBlank() && book.path("callNo").isArray()) {
                callNumber = Jsoup.parse(book.path("callNo").path(0).asText("")).text();
            }
            JsonNode count = book.path("physicalCount");
            Long copies = count.isIntegralNumber() && count.asLong() >= 0 ? count.asLong() : null;
            books.add(new CatalogPage.Book(id, title, text(book, "author"), text(book, "publisher"),
                    text(book, "publishYear"), text(book, "isbn"), callNumber, text(book, "adstract"), copies));
        }
        return new CatalogPage(total, page, pageSize, List.copyOf(books));
    }

    /** 仅用户打开馆藏时查询，状态不写入长期缓存，也不等同于允许借阅。@author itsjony01 @date 2026-10-01 */
    public HoldingsPage holdings(String id, int page, int pageSize) {
        JsonNode data = post("/find/physical/groupitems", Map.of("recordId", id, "page", page,
                "rows", pageSize, "callNo", "", "sortType", 0, "isUnify", true));
        long total = total(data, "totalCount", "list");
        List<HoldingsPage.Holding> items = new ArrayList<>();
        for (JsonNode item : data.path("list")) {
            String location = text(item, "curLocationName");
            if (location.isBlank()) location = text(item, "locationName");
            items.add(new HoldingsPage.Holding(identifier(item, "itemId"), text(item, "barcode"),
                    text(item, "callNo"), text(item, "libName"), location,
                    text(item, "shelfNo"), text(item, "processType")));
        }
        return new HoldingsPage(total, page, pageSize, List.copyOf(items));
    }

    /** 固定上游地址和学院标识，校验 HTTPS 证书，并将故障转换为固定错误码。@author itsjony01 @date 2026-10-01 */
    private JsonNode post(String path, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                    .header("Content-Type", "application/json;charset=UTF-8")
                    //200960是bjkjdxtjxy的学院标志 来自贝克小盒子
                    .header("Accept", "application/json").header("groupCode", "200960")
                    .header("x-lang", "CHI").header("Referer", origin + "/")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429 || response.statusCode() == 503) {
                throw new ProtocolException(HttpStatus.SERVICE_UNAVAILABLE, "LIBRARY_BUSY", "Library is busy");
            }
            if (response.statusCode() != 200) throw unavailable();
            JsonNode root;
            try {
                root = mapper.readTree(response.body());
            } catch (IOException exception) {
                throw formatError();
            }
            if (root == null || !root.path("success").isBoolean()) throw formatError();
            if (!root.path("success").asBoolean()) throw unavailable();
            if (!root.path("data").isObject()) throw formatError();
            return root.path("data");
        } catch (HttpTimeoutException exception) {
            throw new ProtocolException(HttpStatus.GATEWAY_TIMEOUT, "LIBRARY_TIMEOUT", "Library request timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private static long total(JsonNode data, String countField, String itemsField) {
        JsonNode count = data.path(countField);
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.asLong() < 0
                || !data.path(itemsField).isArray() || count.asLong() < data.path(itemsField).size()) {
            throw formatError();
        }
        return count.asLong();
    }

    private static String identifier(JsonNode data, String field) {
        String value = data.path(field).asText("");
        if (!value.matches("[1-9][0-9]{0,17}")) throw formatError();
        return value;
    }

    private static String text(JsonNode data, String field) {
        return Jsoup.parse(data.path(field).asText("")).text();
    }

    private static ProtocolException unavailable() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "LIBRARY_UNAVAILABLE", "Library service unavailable");
    }

    private static ProtocolException formatError() {
        return new ProtocolException(HttpStatus.BAD_GATEWAY, "LIBRARY_FORMAT_CHANGED", "Library response format changed");
    }
}
