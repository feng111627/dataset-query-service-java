package com.github.feng111627.datasetqueryservicejava.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 端到端：
 * 数据集注册/兼容更新/写入/查询，以及统一错误结构
 * （4xx 调用方错误与 500 分开，底层解析异常不透出）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasetApiTest {

    @Autowired
    private MockMvc mvc;

    private static final String SCHEMA = """
            {
              "fields": [
                {"name": "id", "type": "INTEGER", "required": true},
                {"name": "name", "type": "STRING", "required": true},
                {"name": "age", "type": "INTEGER", "required": false}
              ]
            }
            """;

    @Nested
    @DisplayName("数据集注册")
    class Registration {

        @Test
        @DisplayName("POST 注册成功返回 201 与 schema 回显")
        void registerOk() throws Exception {
            mvc.perform(post("/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "people", "schema": %s}
                                    """.formatted(SCHEMA)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("people"))
                    .andExpect(jsonPath("$.recordCount").value(0))
                    .andExpect(jsonPath("$.schema.fields[0].name").value("id"));
        }

        @Test
        @DisplayName("POST 重复注册 -> 409 DATASET_ALREADY_EXISTS")
        void duplicateRegister() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"dup\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());
            mvc.perform(post("/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"dup\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATASET_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("schema 类型字符串非法 -> 400 MALFORMED_REQUEST，且不暴露异常类名")
        void badFieldType() throws Exception {
            mvc.perform(post("/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "badtype", "schema": {"fields": [
                                      {"name": "id", "type": "FLOAT"}
                                    ]}}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("IllegalArgumentException"))));
        }

        @Test
        @DisplayName("请求体含未知字段 -> 400，走统一错误结构")
        void unknownJsonField() throws Exception {
            mvc.perform(post("/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"x\", \"schema\": " + SCHEMA + ", \"bogus\": 1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        @DisplayName("请求体不是 JSON -> 400 MALFORMED_REQUEST，不透出 Jackson 异常细节")
        void malformedJson() throws Exception {
            mvc.perform(post("/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("JsonParseException"))));
        }
    }

    @Nested
    @DisplayName("schema 兼容演进")
    class Evolution {

        @Test
        @DisplayName("PUT 新增可选字段：兼容，返回 200")
        void compatibleEvolution() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"evo\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());

            mvc.perform(put("/datasets/evo")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"schema": {"fields": [
                                      {"name": "id", "type": "INTEGER", "required": true},
                                      {"name": "name", "type": "STRING", "required": true},
                                      {"name": "age", "type": "INTEGER", "required": false},
                                      {"name": "city", "type": "STRING", "required": false}
                                    ]}}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schema.fields.length()").value(4));
        }

        @Test
        @DisplayName("PUT 删除字段：不兼容 -> 409 SCHEMA_INCOMPATIBLE，原因可辨")
        void incompatibleEvolution() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"evobad\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());

            mvc.perform(put("/datasets/evobad")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"schema": {"fields": [
                                      {"name": "id", "type": "INTEGER", "required": true},
                                      {"name": "name", "type": "STRING", "required": true}
                                    ]}}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SCHEMA_INCOMPATIBLE"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("age")));
        }

        @Test
        @DisplayName("PUT 到全新数据集：注册成功返回 201")
        void putCreatesWhenAbsent() throws Exception {
            mvc.perform(put("/datasets/brand-new")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("写入与校验")
    class Writing {

        @Test
        @DisplayName("合法写入返回 201 与水位；GET 能看到 count")
        void writeOk() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"w\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());

            mvc.perform(post("/datasets/w/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"records": [
                                      {"id": 1, "name": "alice", "age": 30},
                                      {"id": 2, "name": "bob"}
                                    ]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accepted").value(2))
                    .andExpect(jsonPath("$.fromId").value(1))
                    .andExpect(jsonPath("$.toId").value(2))
                    .andExpect(jsonPath("$.watermark").value(2));

            mvc.perform(get("/datasets/w"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recordCount").value(2));
        }

        @Test
        @DisplayName("缺失/类型错/未知字段同时出现 -> 422 VALIDATION_FAILED，details 中三类 kind 可区分")
        void writeValidationErrors() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"v\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());

            mvc.perform(post("/datasets/v/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"records": [
                                      {"id": "x", "extra": 9}
                                    ]}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.length()").value(3))
                    .andExpect(jsonPath("$.details[?(@.kind=='missing_field')].field")
                            .value(org.hamcrest.Matchers.hasItem("name")))
                    .andExpect(jsonPath("$.details[?(@.kind=='type_mismatch')].field")
                            .value(org.hamcrest.Matchers.hasItem("id")))
                    .andExpect(jsonPath("$.details[?(@.kind=='unknown_field')].field")
                            .value(org.hamcrest.Matchers.hasItem("extra")));

            // 整批拒绝：数据集仍为空
            mvc.perform(get("/datasets/v"))
                    .andExpect(jsonPath("$.recordCount").value(0));
        }

        @Test
        @DisplayName("向不存在的数据集写入 -> 404 DATASET_NOT_FOUND")
        void writeToMissingDataset() throws Exception {
            mvc.perform(post("/datasets/ghost/records")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"records\": [{\"id\": 1}]}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("查询 HTTP")
    class Querying {

        @Test
        @DisplayName("过滤+选列+分页+watermark 的完整往返")
        void queryRoundTrip() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"q\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());
            mvc.perform(post("/datasets/q/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"records": [
                              {"id": 1, "name": "alice", "age": 30},
                              {"id": 2, "name": "bob", "age": 25},
                              {"id": 3, "name": "carol", "age": 40}
                            ]}
                            """))
                    .andExpect(status().isCreated());

            // 首页
            mvc.perform(post("/datasets/q/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"filters": [{"field": "age", "op": "GTE", "value": 25}],
                                     "select": ["id", "name"],
                                     "limit": 2, "offset": 0}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.watermark").value(3))
                    .andExpect(jsonPath("$.totalMatched").value(3))
                    .andExpect(jsonPath("$.hasMore").value(true))
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    .andExpect(jsonPath("$.rows[0].id").value(1))
                    .andExpect(jsonPath("$.rows[0].age").doesNotExist());

            // 续页回传 watermark
            mvc.perform(post("/datasets/q/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"select": ["id", "name"], "limit": 2, "offset": 2, "watermark": 3}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rows.length()").value(1))
                    .andExpect(jsonPath("$.rows[0].id").value(3))
                    .andExpect(jsonPath("$.hasMore").value(false));
        }

        @Test
        @DisplayName("聚合查询：按 name 分组计数求和")
        void aggregationQuery() throws Exception {
            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"qa\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());
            mvc.perform(post("/datasets/qa/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"records": [
                              {"id": 1, "name": "a", "age": 10},
                              {"id": 2, "name": "b", "age": 20},
                              {"id": 3, "name": "a", "age": 30}
                            ]}
                            """))
                    .andExpect(status().isCreated());

            mvc.perform(post("/datasets/qa/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"groupBy": ["name"],
                                     "aggregations": [
                                       {"op": "COUNT", "field": null, "alias": "cnt"},
                                       {"op": "SUM", "field": "age", "alias": "age_sum"}
                                     ]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.aggregated").value(true))
                    .andExpect(jsonPath("$.rows.length()").value(2))
                    .andExpect(jsonPath("$.rows[0].name").value("a"))
                    .andExpect(jsonPath("$.rows[0].cnt").value(2))
                    .andExpect(jsonPath("$.rows[0].age_sum").value(40.0));
        }

        @Test
        @DisplayName("查询不存在的数据集 -> 404；非法查询体 -> 400 INVALID_QUERY")
        void queryErrors() throws Exception {
            mvc.perform(post("/datasets/missing/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DATASET_NOT_FOUND"));

            mvc.perform(post("/datasets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"qe\", \"schema\": " + SCHEMA + "}"))
                    .andExpect(status().isCreated());

            mvc.perform(post("/datasets/qe/query")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"filters\": [{\"field\": \"nope\", \"op\": \"EQ\", \"value\": 1}]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_QUERY"));
        }
    }
}
