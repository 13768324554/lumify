package io.lumify.opennlpme;

import com.google.inject.Injector;
import io.lumify.core.config.HashMapConfigurationLoader;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.ingest.graphProperty.TermMentionFilter;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.termMention.TermMentionRepository;
import io.lumify.core.security.DirectVisibilityTranslator;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.mapreduce.Mapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.securegraph.Direction;
import org.securegraph.Vertex;
import org.securegraph.Visibility;
import org.securegraph.inmemory.InMemoryAuthorizations;
import org.securegraph.inmemory.InMemoryGraph;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.securegraph.util.IterableUtils.toList;

@RunWith(MockitoJUnitRunner.class)
public class OpenNLPMaximumEntropyExtractorGraphPropertyWorkerTest {
    private static final String RESOURCE_CONFIG_DIR = "/fs/conf/opennlp";

    private OpenNLPMaximumEntropyExtractorGraphPropertyWorker extractor;

    @Mock
    private Mapper.Context context;

    @Mock
    private User user;
    private String text = "This is a sentenc®, written by Bob Robértson, who curréntly makes 2 million "
            + "a year. If by 1:30, you don't know what you are doing, you should go watch CNN and see "
            + "what the latest is on the Benghazi nonsense. I'm 47% sure that this test will pass, but will it?";

    private InMemoryAuthorizations authorizations;
    private InMemoryGraph graph;
    private VisibilityTranslator visibilityTranslator = new DirectVisibilityTranslator();

    @Before
    public void setUp() throws Exception {
        graph = new InMemoryGraph();

        Map config = new HashMap();
        config.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY, "http://lumify.io/test#artifactHasEntity");
        io.lumify.core.config.Configuration configuration = new HashMapConfigurationLoader(config).createConfiguration();

        extractor = new OpenNLPMaximumEntropyExtractorGraphPropertyWorker();
        extractor.setConfiguration(configuration);
        extractor.setGraph(graph);

        Map<String, String> stormConf = new HashMap<String, String>();
        stormConf.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_LOCATION, "http://lumify.io/test#location");
        stormConf.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_ORGANIZATION, "http://lumify.io/test#organization");
        stormConf.put(io.lumify.core.config.Configuration.ONTOLOGY_IRI_PERSON, "http://lumify.io/test#person");
        stormConf.put(OpenNLPMaximumEntropyExtractorGraphPropertyWorker.PATH_PREFIX_CONFIG, "file:///" + getClass().getResource(RESOURCE_CONFIG_DIR).getFile());

        FileSystem hdfsFileSystem = FileSystem.get(new Configuration());
        authorizations = new InMemoryAuthorizations(TermMentionRepository.VISIBILITY);
        Injector injector = null;
        List<TermMentionFilter> termMentionFilters = new ArrayList<TermMentionFilter>();
        GraphPropertyWorkerPrepareData workerPrepareData = new GraphPropertyWorkerPrepareData(stormConf, termMentionFilters, hdfsFileSystem, user, authorizations, injector);
        extractor.prepare(workerPrepareData);
    }

    @Test
    public void testEntityExtraction() throws Exception {
        Vertex vertex = graph.prepareVertex("v1", new Visibility(""))
                .setProperty("text", "none", new Visibility(""))
                .save(new InMemoryAuthorizations());

        GraphPropertyWorkData workData = new GraphPropertyWorkData(vertex, vertex.getProperty("text"), null, null);
        extractor.setVisibilityTranslator(visibilityTranslator);
        extractor.execute(new ByteArrayInputStream(text.getBytes("UTF-8")), workData);

        List<Vertex> termMentions = toList(vertex.getVertices(Direction.OUT, LumifyProperties.TERM_MENTION_LABEL_HAS_TERM_MENTION, authorizations));

        assertEquals(3, termMentions.size());

        boolean foundBobRobertson = false;
        boolean foundBenghazi = false;
        boolean foundCnn = false;
        for (Vertex termMention : termMentions) {
            String title = LumifyProperties.TITLE.getPropertyValue(termMention);

            long start = LumifyProperties.TERM_MENTION_START_OFFSET.getPropertyValue(termMention);
            long end = LumifyProperties.TERM_MENTION_END_OFFSET.getPropertyValue(termMention);
            String conceptType = LumifyProperties.CONCEPT_TYPE.getPropertyValue(termMention);

            if (title.equals("Bob Robértson")) {
                foundBobRobertson = true;
                assertEquals("http://lumify.io/test#person", conceptType);
                assertEquals(31, start);
                assertEquals(44, end);
            } else if (title.equals("Benghazi")) {
                foundBenghazi = true;
                assertEquals("http://lumify.io/test#location", conceptType);
                assertEquals(189, start);
                assertEquals(197, end);
            } else if (title.equals("CNN")) {
                foundCnn = true;
                assertEquals("http://lumify.io/test#organization", conceptType);
                assertEquals(151, start);
                assertEquals(154, end);
            }
        }
        assertTrue("could not find bob robertson", foundBobRobertson);
        assertTrue("could not find benghazi", foundBenghazi);
        assertTrue("could not find cnn", foundCnn);
    }
}
