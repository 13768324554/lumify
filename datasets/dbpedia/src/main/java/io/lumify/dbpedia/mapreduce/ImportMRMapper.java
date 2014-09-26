package io.lumify.dbpedia.mapreduce;

import io.lumify.core.config.Configuration;
import io.lumify.core.config.HashMapConfigurationLoader;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.mapreduce.LumifyElementMapperBase;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.user.AuthorizationRepository;
import io.lumify.core.model.user.InMemoryAuthorizationRepository;
import io.lumify.dbpedia.mapreduce.model.LineData;
import io.lumify.dbpedia.mapreduce.model.LinkValue;
import io.lumify.securegraph.model.ontology.SecureGraphOntologyRepository;
import io.lumify.wikipedia.WikipediaConstants;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Counter;
import org.securegraph.Vertex;
import org.securegraph.VertexBuilder;
import org.securegraph.Visibility;
import org.securegraph.accumulo.AccumuloAuthorizations;
import org.securegraph.accumulo.mapreduce.SecureGraphMRUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ImportMRMapper extends LumifyElementMapperBase<LongWritable, Text> {
    private static final String DBPEDIA_ID_PREFIX = "DBPEDIA_";
    private Counter linesProcessedCounter;
    private Visibility visibility;
    private AccumuloAuthorizations authorizations;
    private SecureGraphOntologyRepository ontologyRepository;
    private static final Map<String, Integer> conceptTypeDepthCache = new HashMap<String, Integer>();

    public static String getDbpediaEntityVertexId(String pageTitle) {
        return DBPEDIA_ID_PREFIX + pageTitle.trim().toLowerCase();
    }

    private String getEntityHasWikipediaPageEdgeId(Vertex entityVertex, Vertex pageVertex) {
        return DBPEDIA_ID_PREFIX + entityVertex.getId() + "_HAS_PAGE_" + pageVertex.getId();
    }

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        super.setup(context);

        this.visibility = new Visibility("");
        this.authorizations = new AccumuloAuthorizations();
        AuthorizationRepository authorizationRepository = new InMemoryAuthorizationRepository();
        try {
            Map configurationMap = SecureGraphMRUtils.toMap(context.getConfiguration());
            Configuration config = HashMapConfigurationLoader.load(configurationMap);
            this.ontologyRepository = new SecureGraphOntologyRepository(getGraph(), config, authorizationRepository);
        } catch (Exception e) {
            throw new IOException("Could not configure secure graph ontology repository", e);
        }
        linesProcessedCounter = context.getCounter(DbpediaImportCounters.LINES_PROCESSED);
    }

    @Override
    protected void safeMap(LongWritable key, Text line, Context context) throws Exception {
        String lineString = line.toString().trim();
        try {
            if (lineString.length() == 0) {
                return;
            }
            if (lineString.startsWith("#")) {
                return;
            }

            LineData lineData = LineData.parse(lineString);

            Vertex dbpediaEntityVertex = createDbpediaEntityVertex(lineData);

            if (lineData.getValue() instanceof LinkValue) {
                LinkValue linkValue = (LinkValue) lineData.getValue();
                if (!lineData.getPropertyIri().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
                    createLinkToDbpediaEntity(lineData, dbpediaEntityVertex, linkValue);
                }
            }

            linesProcessedCounter.increment(1);
        } catch (Throwable ex) {
            throw new LumifyException("Could not process line: " + lineString, ex);
        }
    }

    private void createLinkToDbpediaEntity(LineData lineData, Vertex pageVertex, LinkValue linkValue) {
        String linkedPageVertexId = WikipediaConstants.getWikipediaPageVertexId(linkValue.getPageTitle());
        VertexBuilder linkedPageVertexBuilder = prepareVertex(linkedPageVertexId, visibility);
        LumifyProperties.CONCEPT_TYPE.setProperty(linkedPageVertexBuilder, WikipediaConstants.WIKIPEDIA_PAGE_CONCEPT_URI, visibility);

        Map<String, Object> linkedTitleMetadata = new HashMap<String, Object>();
        LumifyProperties.CONFIDENCE.setMetadata(linkedTitleMetadata, 0.1);
        LumifyProperties.TITLE.addPropertyValue(linkedPageVertexBuilder, ImportMR.MULTI_VALUE_KEY, linkValue.getPageTitle(), linkedTitleMetadata, visibility);

        Vertex linkedPageVertex = linkedPageVertexBuilder.save(authorizations);

        String label = lineData.getPropertyIri();
        String edgeId = pageVertex.getId() + "_" + label + "_" + linkedPageVertex.getId();
        addEdge(edgeId, pageVertex, linkedPageVertex, label, visibility, authorizations);
    }

    private Vertex createDbpediaEntityVertex(LineData lineData) {
        Vertex pageVertex = createPageVertex(lineData);

        String dbpediaEntityVertexId = getDbpediaEntityVertexId(lineData.getPageTitle());
        VertexBuilder entityVertexBuilder = prepareVertex(dbpediaEntityVertexId, visibility);

        Map<String, Object> conceptTypeMetadata = new HashMap<String, Object>();
        LumifyProperties.CONFIDENCE.setMetadata(conceptTypeMetadata, 0.1);
        LumifyProperties.CONCEPT_TYPE.addPropertyValue(entityVertexBuilder, ImportMR.MULTI_VALUE_KEY, "http://www.w3.org/2002/07/owl#Thing", conceptTypeMetadata, visibility);

        Map<String, Object> titleMetadata = new HashMap<String, Object>();
        LumifyProperties.CONFIDENCE.setMetadata(titleMetadata, 0.1);
        LumifyProperties.TITLE.addPropertyValue(entityVertexBuilder, ImportMR.MULTI_VALUE_KEY, lineData.getPageTitle(), titleMetadata, visibility);

        if (lineData.getPropertyIri().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type") && lineData.getValue() instanceof LinkValue) {
            LinkValue linkValue = (LinkValue) lineData.getValue();

            Integer ontologyDepth = getConceptDepth(linkValue.getValueString());
            if (ontologyDepth != null) {
                conceptTypeMetadata = new HashMap<String, Object>();
                LumifyProperties.CONFIDENCE.setMetadata(conceptTypeMetadata, 0.2 + ((double) ontologyDepth / 1000.0));
                String multiValueKey = ImportMR.MULTI_VALUE_KEY + "#" + linkValue.getValueString();
                LumifyProperties.CONCEPT_TYPE.addPropertyValue(entityVertexBuilder, multiValueKey, linkValue.getValueString(), conceptTypeMetadata, visibility);
            }
        }

        if (!(lineData.getValue() instanceof LinkValue)) {
            String multiValueKey = lineData.getValue().getValueString();
            entityVertexBuilder.addPropertyValue(multiValueKey, lineData.getPropertyIri(), lineData.getValue().getValue(), visibility);
        }

        Vertex entityVertex = entityVertexBuilder.save(authorizations);

        String edgeId = getEntityHasWikipediaPageEdgeId(entityVertex, pageVertex);
        addEdge(edgeId, entityVertex, pageVertex, DbpediaOntology.EDGE_LABEL_ENTITY_HAS_WIKIPEDIA_PAGE, visibility, authorizations);

        return entityVertex;
    }

    private Integer getConceptDepth(String conceptIri) {
        if (conceptTypeDepthCache.containsKey(conceptIri)) {
            return conceptTypeDepthCache.get(conceptIri);
        }

        Concept concept = this.ontologyRepository.getConceptByIRI(conceptIri);
        if (concept == null) {
            conceptTypeDepthCache.put(conceptIri, null);
            return null;
        }
        int depth = 0;
        while (true) {
            Concept parentConcept = this.ontologyRepository.getParentConcept(concept);
            if (parentConcept == null) {
                break;
            }
            depth++;
            concept = parentConcept;
        }
        conceptTypeDepthCache.put(conceptIri, depth);
        return depth;
    }

    private Vertex createPageVertex(LineData lineData) {
        String wikipediaPageVertexId = WikipediaConstants.getWikipediaPageVertexId(lineData.getPageTitle());
        VertexBuilder pageVertexBuilder = prepareVertex(wikipediaPageVertexId, visibility);
        LumifyProperties.CONCEPT_TYPE.setProperty(pageVertexBuilder, WikipediaConstants.WIKIPEDIA_PAGE_CONCEPT_URI, visibility);

        Map<String, Object> titleMetadata = new HashMap<String, Object>();
        LumifyProperties.CONFIDENCE.setMetadata(titleMetadata, 0.1);
        LumifyProperties.TITLE.addPropertyValue(pageVertexBuilder, ImportMR.MULTI_VALUE_KEY, lineData.getPageTitle(), titleMetadata, visibility);

        return pageVertexBuilder.save(authorizations);
    }
}
