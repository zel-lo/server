package com.zello.zello.domain;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.internal.SchemaCreatorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EntitySchemaTests {
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void mysqlMappingPreservesTheErdContract() throws IOException {
        String contract;
        try (var stream = getClass().getResourceAsStream("/schema/erd-contract.sql")) {
            assertThat(stream).isNotNull();
            contract = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .build();
        try {
            var sources = new MetadataSources(registry);
            entityManagerFactory.getMetamodel().getEntities()
                    .forEach(entity -> sources.addAnnotatedClass(entity.getJavaType()));
            var metadata = sources.buildMetadata();
            Map<String, Table> actual = new LinkedHashMap<>();
            metadata.getEntityBindings().forEach(entity -> actual.put(entity.getTable().getName(), entity.getTable()));
            var matcher = Pattern.compile("CREATE TABLE (\\w+) \\(([\\s\\S]*?)\\) ENGINE=InnoDB[^;]*;").matcher(contract);
            int expectedTables = 0;
            int checkedForeignKeys = 0;
            int checkedConstraints = 0;
            while (matcher.find()) {
                expectedTables++;
                String tableName = matcher.group(1);
                String definition = matcher.group(2);
                assertThat(actual).containsKey(tableName);
                Table table = actual.get(tableName);
                Set<String> columns = table.getColumns().stream().map(Column::getName).collect(Collectors.toSet());
                var columnMatcher = Pattern.compile("(?m)^    (\\w+) (?:BIGINT|INT|BOOLEAN|VARCHAR\\(\\d+\\)|DATETIME\\(6\\)|DATE|TEXT|JSON)([^\\n]*)").matcher(definition);
                int expectedColumns = 0;
                while (columnMatcher.find()) {
                    expectedColumns++;
                    String name = columnMatcher.group(1);
                    assertThat(columns).as(tableName + "." + name).contains(name);
                    Column column = table.getColumns().stream().filter(c -> c.getName().equals(name)).findFirst().orElseThrow();
                    assertThat(column.isNullable()).as(tableName + "." + name + " nullable")
                            .isEqualTo(!columnMatcher.group(2).contains("NOT NULL"));
                }
                assertThat(columns).hasSize(expectedColumns);
                var fkMatcher = Pattern.compile("CONSTRAINT (\\w+) FOREIGN KEY \\(([^)]+)\\)\\s+REFERENCES (\\w+) \\(([^)]+)\\)").matcher(definition);
                while (fkMatcher.find()) {
                    checkedForeignKeys++;
                    String name = fkMatcher.group(1);
                    var fk = table.getForeignKeyCollection().stream().filter(key -> name.equals(key.getName())).findFirst().orElseThrow();
                    assertThat(fk.getColumns().stream().map(Column::getName).toList())
                            .containsExactlyElementsOf(split(fkMatcher.group(2)));
                    assertThat(fk.getReferencedTable().getName()).isEqualTo(fkMatcher.group(3));
                    List<String> referenced = fk.isReferenceToPrimaryKey()
                            ? fk.getReferencedTable().getPrimaryKey().getColumns().stream().map(Column::getName).toList()
                            : fk.getReferencedColumns().stream().map(Column::getName).toList();
                    assertThat(referenced).containsExactlyElementsOf(split(fkMatcher.group(4)));
                }
                var checkMatcher = Pattern.compile("CONSTRAINT (\\w+) CHECK").matcher(definition);
                Set<String> checks = table.getChecks().stream().map(check -> check.getName()).collect(Collectors.toSet());
                while (checkMatcher.find()) {
                    checkedConstraints++;
                    assertThat(checks).as(tableName + " checks").contains(checkMatcher.group(1));
                }
            }
            assertThat(expectedTables).isEqualTo(21);
            assertThat(actual).hasSize(expectedTables);
            assertThat(checkedForeignKeys).isEqualTo(47);
            assertThat(checkedConstraints).isEqualTo(49);
            String mysqlDdl = String.join(";\n", new SchemaCreatorImpl(registry).generateCreationCommands(metadata, false));
            assertThat(mysqlDdl).contains("create table issues", "create table notifications", "json", "auto_increment");
            // Retain the generated MySQL script for inspection without connecting to a server.
            java.nio.file.Path output = java.nio.file.Path.of("build", "reports", "schema", "mysql-entities.sql");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(output, mysqlDdl + ";\n");
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static List<String> split(String columns) {
        return java.util.Arrays.stream(columns.split(",")).map(String::trim).toList();
    }
}
