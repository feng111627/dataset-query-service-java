package com.github.feng111627.datasetqueryservicejava.web;

import com.github.feng111627.datasetqueryservicejava.error.ApiException;
import com.github.feng111627.datasetqueryservicejava.error.ErrorCode;
import com.github.feng111627.datasetqueryservicejava.error.ValidationIssue;
import com.github.feng111627.datasetqueryservicejava.model.DatasetRecord;
import com.github.feng111627.datasetqueryservicejava.model.DatasetSchema;
import com.github.feng111627.datasetqueryservicejava.query.QueryEngine;
import com.github.feng111627.datasetqueryservicejava.query.QueryRequest;
import com.github.feng111627.datasetqueryservicejava.query.QueryResult;
import com.github.feng111627.datasetqueryservicejava.storage.DatasetStore;
import com.github.feng111627.datasetqueryservicejava.validation.RecordValidator;
import com.github.feng111627.datasetqueryservicejava.web.dto.DatasetInfo;
import com.github.feng111627.datasetqueryservicejava.web.dto.RegisterDatasetRequest;
import com.github.feng111627.datasetqueryservicejava.web.dto.UpsertDatasetRequest;
import com.github.feng111627.datasetqueryservicejava.web.dto.WriteRecordsRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据集注册 / 写入 / 查询 HTTP 接口。 */
@RestController
@RequestMapping("/datasets")
public class DatasetController {

    private final DatasetStore store;
    private final QueryEngine queryEngine;
    private final DtoMapper mapper;
    private final RecordValidator validator;

    public DatasetController(DatasetStore store, QueryEngine queryEngine,
                             DtoMapper mapper, RecordValidator validator) {
        this.store = store;
        this.queryEngine = queryEngine;
        this.mapper = mapper;
        this.validator = validator;
    }

    /** 注册新数据集；已存在则 409，需走 PUT 做兼容更新。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatasetInfo register(@RequestBody RegisterDatasetRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST, "请求必须包含非空的 name");
        }
        DatasetSchema schema = mapper.toSchema(request.getSchema());
        store.register(request.getName(), schema, true);
        return infoOf(request.getName());
    }

    /** 注册或兼容更新；可选随附首批记录（先校验/注册，再原子写入）。 */
    @PutMapping("/{name}")
    public ResponseEntity<DatasetInfo> upsert(@PathVariable String name,
                                              @RequestBody UpsertDatasetRequest request) {
        if (request == null || request.getSchema() == null) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST,
                    "PUT 请求必须包含 schema（用于注册或兼容更新）");
        }
        DatasetSchema schema = mapper.toSchema(request.getSchema());
        boolean created = store.register(name, schema, false);

        if (request.getRecords() != null && !request.getRecords().isEmpty()) {
            List<DatasetRecord> records = mapper.toRecords(request.getRecords());
            validateOrThrow(schema, records);
            store.append(name, records);
        }
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(infoOf(name));
    }

    @GetMapping("/{name}")
    public DatasetInfo info(@PathVariable String name) {
        return infoOf(name);
    }

    /** 写入一批记录；整批要么全部写入，要么因校验失败全部不写入。 */
    @PostMapping("/{name}/records")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> write(@PathVariable String name,
                                     @RequestBody WriteRecordsRequest request) {
        DatasetSchema schema = store.schemaOf(name)
                .orElseThrow(() -> new ApiException(404, ErrorCode.DATASET_NOT_FOUND,
                        "数据集 '" + name + "' 不存在，请先注册"));
        List<DatasetRecord> records = mapper.toRecords(request == null ? null : request.getRecords());
        validateOrThrow(schema, records);
        long[] range = store.append(name, records);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", records.size());
        response.put("fromId", range[0]);
        response.put("toId", range[1]);
        response.put("watermark", range[1]);
        return response;
    }

    @PostMapping("/{name}/query")
    public QueryResult query(@PathVariable String name,
                             @RequestBody QueryRequest request) {
        if (request == null) {
            throw new ApiException(400, ErrorCode.MALFORMED_REQUEST, "查询体不能为空");
        }
        return queryEngine.execute(name, request);
    }

    private void validateOrThrow(DatasetSchema schema, List<DatasetRecord> records) {
        List<ValidationIssue> issues = validator.validate(schema, records);
        if (!issues.isEmpty()) {
            throw new ApiException(422, ErrorCode.VALIDATION_FAILED,
                    "有 " + issues.size() + " 条记录未通过 schema 校验，整批写入已拒绝",
                    issues);
        }
    }

    private DatasetInfo infoOf(String name) {
        DatasetSchema schema = store.schemaOf(name)
                .orElseThrow(() -> new ApiException(404, ErrorCode.DATASET_NOT_FOUND,
                        "数据集 '" + name + "' 不存在，请先注册"));
        return new DatasetInfo(name, mapper.toSchemaDto(schema), store.countOf(name));
    }
}
