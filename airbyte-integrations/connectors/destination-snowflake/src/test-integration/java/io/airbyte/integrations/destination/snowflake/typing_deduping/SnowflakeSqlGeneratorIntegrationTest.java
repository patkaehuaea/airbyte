/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.snowflake.typing_deduping;

import static io.airbyte.integrations.destination.snowflake.SnowflakeTestUtils.timestampToString;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import autovalue.shaded.com.google.common.collect.ImmutableMap;
import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.factory.DataSourceFactory;
import io.airbyte.db.jdbc.JdbcDatabase;
import io.airbyte.db.jdbc.JdbcUtils;
import io.airbyte.integrations.base.JavaBaseConstants;
import io.airbyte.integrations.base.destination.typing_deduping.BaseSqlGeneratorIntegrationTest;
import io.airbyte.integrations.base.destination.typing_deduping.StreamId;
import io.airbyte.integrations.destination.snowflake.OssCloudEnvVarConsts;
import io.airbyte.integrations.destination.snowflake.SnowflakeDatabase;
import io.airbyte.integrations.destination.snowflake.SnowflakeTestSourceOperations;
import io.airbyte.integrations.destination.snowflake.SnowflakeTestUtils;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SnowflakeSqlGeneratorIntegrationTest extends BaseSqlGeneratorIntegrationTest<SnowflakeTableDefinition> {

  private static String databaseName;
  private static JdbcDatabase database;
  private static DataSource dataSource;

  @BeforeAll
  public static void setupSnowflake() {
    final JsonNode config = Jsons.deserialize(IOs.readFile(Path.of("secrets/1s1t_internal_staging_config.json")));
    databaseName = config.get(JdbcUtils.DATABASE_KEY).asText();
    dataSource = SnowflakeDatabase.createDataSource(config, OssCloudEnvVarConsts.AIRBYTE_OSS);
    database = SnowflakeDatabase.getDatabase(dataSource);
  }

  @AfterAll
  public static void teardownSnowflake() throws Exception {
    DataSourceFactory.close(dataSource);
  }

  @Override
  protected SnowflakeSqlGenerator getSqlGenerator() {
    return new SnowflakeSqlGenerator();
  }

  @Override
  protected SnowflakeDestinationHandler getDestinationHandler() {
    return new SnowflakeDestinationHandler(databaseName, database);
  }

  @Override
  protected void createNamespace(final String namespace) throws SQLException {
    database.execute("CREATE SCHEMA IF NOT EXISTS \"" + namespace + '"');
  }

  @Override
  protected void createRawTable(final StreamId streamId) throws Exception {
    database.execute(new StringSubstitutor(Map.of(
        "raw_table_id", streamId.rawTableId(SnowflakeSqlGenerator.QUOTE))).replace(
            """
            CREATE TABLE ${raw_table_id} (
              "_airbyte_raw_id" TEXT NOT NULL,
              "_airbyte_data" VARIANT NOT NULL,
              "_airbyte_extracted_at" TIMESTAMP_TZ NOT NULL,
              "_airbyte_loaded_at" TIMESTAMP_TZ
            )
            """));
  }

  @Override
  protected List<JsonNode> dumpRawTableRecords(final StreamId streamId) throws Exception {
    return SnowflakeTestUtils.dumpRawTable(database, streamId.rawTableId(SnowflakeSqlGenerator.QUOTE));
  }

  @Override
  protected List<JsonNode> dumpFinalTableRecords(final StreamId streamId, final String suffix) throws Exception {
    return SnowflakeTestUtils.dumpFinalTable(
        database,
        databaseName,
        streamId.finalNamespace(),
        streamId.finalName() + suffix);
  }

  @Override
  protected void teardownNamespace(final String namespace) throws SQLException {
    database.execute("DROP SCHEMA IF EXISTS \"" + namespace + '"');
  }

  @Override
  protected void insertFinalTableRecords(final boolean includeCdcDeletedAt,
                                         final StreamId streamId,
                                         final String suffix,
                                         final List<JsonNode> records)
      throws Exception {
    final List<String> columnNames = includeCdcDeletedAt ? FINAL_TABLE_COLUMN_NAMES_CDC : FINAL_TABLE_COLUMN_NAMES;
    final String cdcDeletedAtName = includeCdcDeletedAt ? ",\"_ab_cdc_deleted_at\"" : "";
    final String cdcDeletedAtExtract = includeCdcDeletedAt ? ",column19" : "";
    final String recordsText = records.stream()
        // For each record, convert it to a string like "(rawId, extractedAt, loadedAt, data)"
        .map(record -> columnNames.stream()
            .map(record::get)
            .map(this::dollarQuoteWrap)
            .collect(joining(",")))
        .map(row -> "(" + row + ")")
        .collect(joining(","));

    database.execute(new StringSubstitutor(
        Map.of(
            "final_table_id", streamId.finalTableId(SnowflakeSqlGenerator.QUOTE, suffix),
            "cdc_deleted_at_name", cdcDeletedAtName,
            "cdc_deleted_at_extract", cdcDeletedAtExtract,
            "records", recordsText),
        "#{",
        "}").replace(
            // Similar to insertRawTableRecords, some of these columns are declared as string and wrapped in
            // parse_json().
            """
            INSERT INTO #{final_table_id} (
              "_airbyte_raw_id",
              "_airbyte_extracted_at",
              "_airbyte_meta",
              "id1",
              "id2",
              "updated_at",
              "struct",
              "array",
              "string",
              "number",
              "integer",
              "boolean",
              "timestamp_with_timezone",
              "timestamp_without_timezone",
              "time_with_timezone",
              "time_without_timezone",
              "date",
              "unknown"
              #{cdc_deleted_at_name}
            )
            SELECT
              column1,
              column2,
              PARSE_JSON(column3),
              column4,
              column5,
              column6,
              PARSE_JSON(column7),
              PARSE_JSON(column8),
              column9,
              column10,
              column11,
              column12,
              column13,
              column14,
              column15,
              column16,
              column17,
              PARSE_JSON(column18)
              #{cdc_deleted_at_extract}
            FROM VALUES
              #{records}
            """));
  }

  private String dollarQuoteWrap(final JsonNode node) {
    if (node == null) {
      return "NULL";
    }
    final String stringContents = node.isTextual() ? node.asText() : node.toString();
    // Use dollar quotes to avoid needing to escape quotes
    return StringUtils.wrap(stringContents.replace("$$", "\\$\\$"), "$$");
  }

  @Override
  protected void insertRawTableRecords(final StreamId streamId, final List<JsonNode> records) throws Exception {
    final String recordsText = records.stream()
        // For each record, convert it to a string like "(rawId, extractedAt, loadedAt, data)"
        .map(record -> JavaBaseConstants.V2_RAW_TABLE_COLUMN_NAMES
            .stream()
            .map(record::get)
            .map(this::dollarQuoteWrap)
            .collect(joining(",")))
        .map(row -> "(" + row + ")")
        .collect(joining(","));
    database.execute(new StringSubstitutor(
        Map.of(
            "raw_table_id", streamId.rawTableId(SnowflakeSqlGenerator.QUOTE),
            "records_text", recordsText),
        // Use different delimiters because we're using dollar quotes in the query.
        "#{",
        "}").replace(
            // Snowflake doesn't let you directly insert a parse_json expression, so we have to use a subquery.
            """
            INSERT INTO #{raw_table_id} (
              "_airbyte_raw_id",
              "_airbyte_extracted_at",
              "_airbyte_loaded_at",
              "_airbyte_data"
            )
            SELECT
              column1,
              column2,
              column3,
              PARSE_JSON(column4)
            FROM VALUES
              #{records_text};
            """));
  }

  @Override
  @Test
  public void testCreateTableIncremental() throws Exception {
    final String sql = generator.createTable(incrementalDedupStream, "", false);
    destinationHandler.execute(sql);

    final Optional<String> tableKind = database.queryJsons(String.format("SHOW TABLES LIKE '%s' IN SCHEMA \"%s\";", "users_final", namespace))
        .stream().map(record -> record.get("kind").asText())
        .findFirst();
    final Map<String, String> columns = database.queryJsons(
        """
        SELECT column_name, data_type, numeric_precision, numeric_scale
        FROM information_schema.columns
        WHERE table_catalog = ?
          AND table_schema = ?
          AND table_name = ?
        ORDER BY ordinal_position;
        """,
        databaseName,
        namespace,
        "users_final").stream()
        .collect(toMap(
            record -> record.get("COLUMN_NAME").asText(),
            record -> {
              final String type = record.get("DATA_TYPE").asText();
              if (type.equals("NUMBER")) {
                return String.format("NUMBER(%s, %s)", record.get("NUMERIC_PRECISION").asText(),
                    record.get("NUMERIC_SCALE").asText());
              }
              return type;
            }));
    assertAll(
        () -> assertEquals(Optional.of("TABLE"), tableKind, "Table should be permanent, not transient"),
        () -> assertEquals(
            ImmutableMap.builder()
                .put("_airbyte_raw_id", "TEXT")
                .put("_airbyte_extracted_at", "TIMESTAMP_TZ")
                .put("_airbyte_meta", "VARIANT")
                .put("id1", "NUMBER(38, 0)")
                .put("id2", "NUMBER(38, 0)")
                .put("updated_at", "TIMESTAMP_TZ")
                .put("struct", "OBJECT")
                .put("array", "ARRAY")
                .put("string", "TEXT")
                .put("number", "FLOAT")
                .put("integer", "NUMBER(38, 0)")
                .put("boolean", "BOOLEAN")
                .put("timestamp_with_timezone", "TIMESTAMP_TZ")
                .put("timestamp_without_timezone", "TIMESTAMP_NTZ")
                .put("time_with_timezone", "TEXT")
                .put("time_without_timezone", "TIME")
                .put("date", "DATE")
                .put("unknown", "VARIANT")
                .build(),
            columns));
  }

  @Override
  protected void createV1RawTable(final StreamId v1RawTable) throws Exception {
    database.execute(String.format(
        """
            CREATE SCHEMA IF NOT EXISTS %s;
            CREATE TABLE IF NOT EXISTS %s.%s (
              %s VARCHAR PRIMARY KEY,
              %s VARIANT,
              %s TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp()
            ) data_retention_time_in_days = 0;
        """,
        v1RawTable.rawNamespace(),
        v1RawTable.rawNamespace(),
        v1RawTable.rawName(),
        JavaBaseConstants.COLUMN_NAME_AB_ID,
        JavaBaseConstants.COLUMN_NAME_DATA,
        JavaBaseConstants.COLUMN_NAME_EMITTED_AT));
  }

  @Override
  protected void insertV1RawTableRecords(final StreamId streamId, final List<JsonNode> records) throws Exception {
    final var recordsText = records
        .stream()
        .map(record -> JavaBaseConstants.LEGACY_RAW_TABLE_COLUMNS
            .stream()
            .map(record::get)
            .map(value -> value == null ? "NULL" : value.isTextual() ? value.asText() : value.toString())
            .map(v -> "NULL".equals(v) ? v : StringUtils.wrap(v, "$$"))
            .collect(joining(",")))
        .map(row -> "(%s)".formatted(row))
        .collect(joining(","));
    final var insert = new StringSubstitutor(Map.of(
        "v1_raw_table_id", String.join(".", streamId.rawNamespace(), streamId.rawName()),
        "records", recordsText),
        // Use different delimiters because we're using dollar quotes in the query.
        "#{",
        "}").replace(
            """
            INSERT INTO #{v1_raw_table_id} (_airbyte_ab_id, _airbyte_data, _airbyte_emitted_at)
            SELECT column1, PARSE_JSON(column2), column3 FROM VALUES
              #{records};
            """);
    database.execute(insert);
  }

  @Override
  protected List<JsonNode> dumpV1RawTableRecords(final StreamId streamId) throws Exception {
    final var columns = Stream.of(
        JavaBaseConstants.COLUMN_NAME_AB_ID,
        timestampToString(JavaBaseConstants.COLUMN_NAME_EMITTED_AT),
        JavaBaseConstants.COLUMN_NAME_DATA).collect(joining(","));
    return database.bufferedResultSetQuery(connection -> connection.createStatement().executeQuery(new StringSubstitutor(Map.of(
        "columns", columns,
        "table", String.join(".", streamId.rawNamespace(), streamId.rawName()))).replace(
            """
            SELECT ${columns} FROM ${table} ORDER BY _airbyte_emitted_at ASC
            """)),
        new SnowflakeTestSourceOperations()::rowToJson);
  }

  @Override
  protected void migrationAssertions(final List<JsonNode> v1RawRecords, final List<JsonNode> v2RawRecords) {
    final var v2RecordMap = v2RawRecords.stream().collect(Collectors.toMap(
        record -> record.get(JavaBaseConstants.COLUMN_NAME_AB_RAW_ID).asText(),
        Function.identity()));
    assertAll(
        () -> assertEquals(5, v1RawRecords.size()),
        () -> assertEquals(5, v2RawRecords.size()));
    v1RawRecords.forEach(v1Record -> {
      final var v1id = v1Record.get(JavaBaseConstants.COLUMN_NAME_AB_ID.toUpperCase()).asText();
      assertAll(
          () -> assertEquals(v1id, v2RecordMap.get(v1id).get(JavaBaseConstants.COLUMN_NAME_AB_RAW_ID).asText()),
          () -> assertEquals(v1Record.get(JavaBaseConstants.COLUMN_NAME_EMITTED_AT.toUpperCase()).asText(),
              v2RecordMap.get(v1id).get(JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT).asText()),
          () -> assertNull(v2RecordMap.get(v1id).get(JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT)));
      JsonNode originalData = v1Record.get(JavaBaseConstants.COLUMN_NAME_DATA.toUpperCase());
      JsonNode migratedData = v2RecordMap.get(v1id).get(JavaBaseConstants.COLUMN_NAME_DATA);
      migratedData = migratedData.isTextual() ? Jsons.deserializeExact(migratedData.asText()) : migratedData;
      originalData = originalData.isTextual() ? Jsons.deserializeExact(migratedData.asText()) : originalData;
      // hacky thing because we only care about the data contents.
      // diffRawTableRecords makes some assumptions about the structure of the blob.
      DIFFER.diffFinalTableRecords(List.of(originalData), List.of(migratedData));
    });
  }

}
