package net.tdteam.ustb.library.model;

import java.util.List;

/** 单册馆藏实时信息，保留学校原始状态文案而不推断可借。@author itsjony01 @date 2026-10-01 */
public record HoldingsPage(long total, int page, int pageSize, List<Holding> items) {
    public record Holding(String id, String barcode, String callNumber, String library,
                          String location, String shelf, String status) { }
}
