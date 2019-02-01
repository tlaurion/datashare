package org.icij.datashare;

import net.codestory.http.WebServer;
import net.codestory.http.misc.Env;
import net.codestory.rest.FluentRestTest;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class IndexSourceResourceTest implements FluentRestTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static WebServer server = new WebServer() {
        @Override
        protected Env createEnv() {
            return Env.prod();
        }
    }.startOnRandomPort();
    @Mock Indexer indexer;
    @Override public int port() { return server.port();}

    @Test
    public void test_get_source_file() throws Exception {
        File txtFile = new File(temp.getRoot(), "file.txt");
        write(txtFile, "text content");
        indexFile("my_index", "id_txt", txtFile.toPath(), null);

        get("/api/index/src/my_index/id_txt").should().contain("text content").haveType("text/plain;charset=UTF-8");
    }

    @Test
    public void test_get_source_file_with_routing() throws Exception {
        File htmlFile = new File(temp.getRoot(), "index.html");
        write(htmlFile, "<html>content</html>");
        indexFile("foo_index", "id_html", htmlFile.toPath(), "my_routing");

        get("/api/index/src/foo_index/id_html?routing=my_routing").should().contain("<html>content</html>").haveType("text/html;charset=UTF-8");
    }

    private void indexFile(String index, String _id, Path path, String routing) {
        Document doc = mock(Document.class);
        when(doc.getPath()).thenReturn(path);
        if (routing == null) {
            when(indexer.get(index, _id)).thenReturn(doc);
        } else {
            when(indexer.get(index, _id, routing)).thenReturn(doc);
        }
    }

    @After
    public void tearDown() throws Exception {
        reset(indexer);
    }

    @Before
    public void setUp() {
        initMocks(this);
        server.configure(routes -> routes.add(new IndexResource(new PropertiesProvider(), indexer)).filter(new LocalUserFilter(new PropertiesProvider())));
    }
    static void write(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(UTF_8));
    }
}
