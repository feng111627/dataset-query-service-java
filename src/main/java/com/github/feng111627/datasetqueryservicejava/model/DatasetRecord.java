package com.github.feng111627.datasetqueryservicejava.model;

import java.util.Map;

/**
 * 一条数据集记录。
 *
 * @param id     服务端分配的单调递增 id（1 起），0 表示尚未写入的输入记录
 * @param values 字段名 -> 值（不含 id 本身）
 */
public record DatasetRecord(long id, Map<String, Object> values) {

    public static DatasetRecord input(Map<String, Object> values) {
        return new DatasetRecord(0, values);
    }
}
