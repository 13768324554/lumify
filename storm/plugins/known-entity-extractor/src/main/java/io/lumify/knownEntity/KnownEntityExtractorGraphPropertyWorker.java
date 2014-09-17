package io.lumify.knownEntity;

import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.audit.AuditAction;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.termMention.TermMentionBuilder;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.arabidopsis.ahocorasick.AhoCorasick;
import org.arabidopsis.ahocorasick.SearchResult;
import org.json.JSONObject;
import org.securegraph.*;
import org.supercsv.io.CsvListReader;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.securegraph.util.IterableUtils.singleOrDefault;

public class KnownEntityExtractorGraphPropertyWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(KnownEntityExtractorGraphPropertyWorker.class);
    public static final String PATH_PREFIX_CONFIG = "termextraction.knownEntities.pathPrefix";
    public static final String DEFAULT_PATH_PREFIX = "/lumify/config/knownEntities/";
    private static final String PROCESS = KnownEntityExtractorGraphPropertyWorker.class.getName();
    private AhoCorasick tree;
    private String artifactHasEntityIri;

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);
        String pathPrefix = (String) workerPrepareData.getStormConf().get(PATH_PREFIX_CONFIG);
        if (pathPrefix == null) {
            pathPrefix = DEFAULT_PATH_PREFIX;
        }
        FileSystem fs = workerPrepareData.getHdfsFileSystem();
        this.tree = loadDictionaries(fs, pathPrefix);

        this.artifactHasEntityIri = getConfiguration().get(Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY);
        if (this.artifactHasEntityIri == null) {
            throw new LumifyException("Could not find configuration for " + Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY);
        }
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        String text = IOUtils.toString(in, "UTF-8"); // TODO convert AhoCorasick to use InputStream
        Iterator<SearchResult<Match>> searchResults = tree.search(text.toCharArray());
        Vertex sourceVertex = (Vertex) data.getElement();
        List<Vertex> termMentions = new ArrayList<Vertex>();
        while (searchResults.hasNext()) {
            SearchResult searchResult = searchResults.next();
            JSONObject visibilitySource = data.getVisibilitySourceJson();
            List<Vertex> newTermMentions = outputResultToTermMention(sourceVertex, searchResult, data.getProperty().getKey(), visibilitySource, data.getVisibility());
            termMentions.addAll(newTermMentions);
            getGraph().flush();
        }
        applyTermMentionFilters(sourceVertex, termMentions);
    }

    private List<Vertex> outputResultToTermMention(Vertex sourceVertex, SearchResult<Match> searchResult, String propertyKey, JSONObject visibilitySource, Visibility visibility) {
        List<Vertex> termMentions = new ArrayList<Vertex>();
        for (Match match : searchResult.getOutputs()) {
            int start = searchResult.getLastIndex() - match.getMatchText().length();
            int end = searchResult.getLastIndex();
            String title = match.getEntityTitle();
            String ontologyClassUri = mapToOntologyIri(match.getConceptTitle());

            Vertex resolvedToVertex = findOrAddEntity(title, ontologyClassUri, visibility);
            Edge resolvedEdge = findOrAddEdge(sourceVertex, resolvedToVertex, visibilitySource, visibility);

            Vertex termMention = new TermMentionBuilder()
                    .sourceVertex(sourceVertex)
                    .propertyKey(propertyKey)
                    .start(start)
                    .end(end)
                    .title(title)
                    .conceptIri(ontologyClassUri)
                    .visibilityJson(visibilitySource)
                    .process(PROCESS)
                    .resolvedTo(resolvedToVertex, resolvedEdge)
                    .save(getGraph(), getVisibilityTranslator(), getAuthorizations());
            termMentions.add(termMention);
        }
        return termMentions;
    }

    private Edge findOrAddEdge(Vertex sourceVertex, Vertex resolvedToVertex, JSONObject visibilitySource, Visibility visibility) {
        Edge resolvedEdge = singleOrDefault(sourceVertex.getEdges(resolvedToVertex, Direction.BOTH, getAuthorizations()), null);
        if (resolvedEdge == null) {
            EdgeBuilder resolvedEdgeBuilder = getGraph().prepareEdge(sourceVertex, resolvedToVertex, artifactHasEntityIri, visibility);
            LumifyProperties.VISIBILITY_SOURCE.setProperty(resolvedEdgeBuilder, visibilitySource, visibility);
            resolvedEdge = resolvedEdgeBuilder.save(getAuthorizations());
            getAuditRepository().auditRelationship(AuditAction.CREATE, sourceVertex, resolvedToVertex, resolvedEdge, PROCESS, "", getUser(), visibility);
        }
        return resolvedEdge;
    }

    private Vertex findOrAddEntity(String title, String ontologyClassUri, Visibility visibility) {
        Vertex vertex = singleOrDefault(getGraph().query(getAuthorizations())
                .has(LumifyProperties.TITLE.getPropertyName(), title)
                .has(LumifyProperties.CONCEPT_TYPE.getPropertyName(), ontologyClassUri)
                .vertices(), null);
        if (vertex != null) {
            return vertex;
        }

        VertexBuilder vertexElementMutation = getGraph().prepareVertex(visibility);
        LumifyProperties.TITLE.setProperty(vertexElementMutation, title, visibility);
        LumifyProperties.CONCEPT_TYPE.setProperty(vertexElementMutation, ontologyClassUri, visibility);
        vertex = vertexElementMutation.save(getAuthorizations());
        getGraph().flush();
        return vertex;
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        if (property.getName().equals(LumifyProperties.RAW.getPropertyName())) {
            return false;
        }

        String mimeType = (String) property.getMetadata().get(LumifyProperties.MIME_TYPE.getPropertyName());
        return !(mimeType == null || !mimeType.startsWith("text"));
    }

    private static AhoCorasick loadDictionaries(FileSystem fs, String pathPrefix) throws IOException {
        AhoCorasick tree = new AhoCorasick();
        Path hdfsDirectory = new Path(pathPrefix, "dictionaries");
        if (!fs.exists(hdfsDirectory)) {
            fs.mkdirs(hdfsDirectory);
        }
        for (FileStatus dictionaryFileStatus : fs.listStatus(hdfsDirectory)) {
            Path hdfsPath = dictionaryFileStatus.getPath();
            if (hdfsPath.getName().startsWith(".") || !hdfsPath.getName().endsWith(".dict")) {
                continue;
            }
            LOGGER.info("Loading known entity dictionary %s", hdfsPath.toString());
            String conceptName = FilenameUtils.getBaseName(hdfsPath.getName());
            conceptName = URLDecoder.decode(conceptName, "UTF-8");
            InputStream dictionaryInputStream = fs.open(hdfsPath);
            try {
                addDictionaryEntriesToTree(tree, conceptName, dictionaryInputStream);
            } finally {
                dictionaryInputStream.close();
            }
        }
        tree.prepare();
        return tree;
    }

    private static void addDictionaryEntriesToTree(AhoCorasick tree, String type, InputStream dictionaryInputStream) throws IOException {
        CsvPreference csvPrefs = CsvPreference.EXCEL_PREFERENCE;
        CsvListReader csvReader = new CsvListReader(new InputStreamReader(dictionaryInputStream), csvPrefs);
        List<String> line;
        while ((line = csvReader.read()) != null) {
            if (line.size() != 2) {
                throw new RuntimeException("Invalid number of entries on a line. Expected 2 found " + line.size());
            }
            tree.add(line.get(0), new Match(type, line.get(0), line.get(1)));
        }
    }

    private static class Match {
        private final String conceptTitle;
        private final String entityTitle;
        private final String matchText;

        public Match(String type, String matchText, String entityTitle) {
            conceptTitle = type;
            this.matchText = matchText;
            this.entityTitle = entityTitle;
        }

        private String getConceptTitle() {
            return conceptTitle;
        }

        private String getEntityTitle() {
            return entityTitle;
        }

        private String getMatchText() {
            return matchText;
        }

        @Override
        public String toString() {
            return matchText;
        }
    }
}
