package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.stubbing.Answer;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static java.nio.file.Paths.get;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.icij.datashare.com.Message.Field.*;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.com.Message.Type.INIT_MONITORING;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.nlp.NlpApp.NLP_PARALLELISM_OPT;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.OPENNLP;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class NlpAppTest {
    @Mock private AbstractPipeline pipeline;
    @Mock private Indexer indexer;
    private RedisPublisher publisher = new RedisPublisher(new PropertiesProvider());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    @Test(timeout = 5000)
    public void test_subscriber_mode_for_standalone_extraction() throws Exception {
        runNlpApp("1", 0);

        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id").add(R_ID, "routing").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new ShutdownMessage());

        shutdownNlpApp();
        verify(pipeline, times(1)).process(anyString(), anyString(), any(Language.class));
    }

    @Test(timeout = 5000)
    public void test_consumer_mode_for_multithreaded_server_extraction() throws Exception {
        runNlpApp("2", 0);

        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id1").add(R_ID, "routing1").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id2").add(R_ID, "routing2").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new ShutdownMessage());

        shutdownNlpApp();
        verify(pipeline, times(2)).process(anyString(), anyString(), any(Language.class));
    }

    @Test(timeout = 5000)
    public void test_nlp_app_should_wait_queue_to_be_empty_to_shutdown() throws Exception {
        runNlpApp("1", 200);

        IntStream.range(1,4).forEach(i -> publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id" + i).add(R_ID, "routing" + i).add(INDEX_NAME, local().id)));
        publisher.publish(Channel.NLP, new ShutdownMessage());

        shutdownNlpApp();
        verify(pipeline, times(3)).process(anyString(), anyString(), any(Language.class));
    }

    @Test(timeout = 5000)
    public void test_nlp_app_progress_rate() throws Exception {
        NlpApp nlpApp = runNlpApp("1", 0);

        assertThat(nlpApp.getProgressRate()).isEqualTo(-1);
        publisher.publish(Channel.NLP, new Message(INIT_MONITORING).add(VALUE, "4"));
        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id1").add(R_ID, "routing1").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id2").add(R_ID, "routing2").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new ShutdownMessage());

        shutdownNlpApp();
        assertThat(nlpApp.getProgressRate()).isEqualTo(0.5);
    }

    @Test(timeout = 5000)
    public void test_nlp_app_progress_rate__two_init_add_values() throws Exception {
        NlpApp nlpApp = runNlpApp("1", 0);

        assertThat(nlpApp.getProgressRate()).isEqualTo(-1);
        publisher.publish(Channel.NLP, new Message(INIT_MONITORING).add(VALUE, "4"));
        publisher.publish(Channel.NLP, new Message(INIT_MONITORING).add(VALUE, "6"));
        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id").add(R_ID, "routing").add(INDEX_NAME, local().id));
        publisher.publish(Channel.NLP, new ShutdownMessage());

        shutdownNlpApp();
        assertThat(nlpApp.getProgressRate()).isEqualTo(0.1);
    }

    private NlpApp runNlpApp(String parallelism, int nlpProcessDelayMillis) throws InterruptedException {
        Properties properties = new Properties();
        properties.setProperty(NLP_PARALLELISM_OPT, parallelism);
        properties.setProperty("messageBusAddress", "redis");
        CountDownLatch latch = new CountDownLatch(1);

        when(pipeline.process(anyString(), anyString(), any())).thenAnswer((Answer<Annotations>) invocationOnMock -> {
            if (nlpProcessDelayMillis > 0) Thread.sleep(nlpProcessDelayMillis);
            return new Annotations("docid_mock", Pipeline.Type.CORENLP, Language.FRENCH);
        });
        NlpApp nlpApp = new NlpApp(indexer, pipeline, properties, latch::countDown, 1, local());
        executor.execute(nlpApp);
        latch.await(2, SECONDS);
        return nlpApp;
    }

    private void shutdownNlpApp() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, SECONDS);
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(indexer.get(anyString(), anyString(), anyString())).thenReturn(
                new Document(project("prj"), get("doc/path"), "content", FRENCH, Charset.defaultCharset(),
                        "application/pdf", new HashMap<>(), INDEXED, 432L));
        when(pipeline.getType()).thenReturn(OPENNLP);
        when(pipeline.initialize(any(Language.class))).thenReturn(true);
        when(pipeline.process(anyString(), anyString(), any(Language.class))).thenReturn(
                new Annotations("doc", CORENLP, FRENCH));
    }

    @After
    public void tearDown() throws Exception {
        reset(indexer, pipeline);
    }
}
