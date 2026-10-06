package com.github.feng111627.datasetqueryservicejava.web.dto;

import java.util.List;
import java.util.Map;

/** 写入记录请求体。 */
public class WriteRecordsRequest {
    private List<Map<String, Object>> records;

    public List<Map<String, Object>> getRecords() {
        return records;
    }

    public void setRecords(List<Map<String, Object>> records) {
        this.records = records;
    }
}
