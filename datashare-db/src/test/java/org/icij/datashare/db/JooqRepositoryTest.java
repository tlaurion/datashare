package org.icij.datashare.db;


import org.icij.datashare.text.Document;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.db.DbSetupRule.createSqlite;
import static org.icij.datashare.text.Language.FRENCH;

public class JooqRepositoryTest {
    @Rule public DbSetupRule dbRule = new DbSetupRule(createSqlite());
    private JooqRepository repository = new JooqRepository(new DataSourceConnectionProvider(dbRule.dataSource), SQLDialect.SQLITE);

    @Test
    public void test_create_document() throws Exception {
        Document document = new Document(Paths.get("/path/to/doc"), "content", FRENCH,
                Charset.defaultCharset(), "test/plain", new HashMap<String, String>() {{
                    put("key 1", "value 1");
                    put("key 2", "value 2");
        }}, Document.Status.INDEXED);

        repository.create(document);

        Document actual = repository.getDocument(document.getId());
        assertThat(actual).isEqualTo(document);
        assertThat(actual.getMetadata()).isEqualTo(document.getMetadata());
    }
}