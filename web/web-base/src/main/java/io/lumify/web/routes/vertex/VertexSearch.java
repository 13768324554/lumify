package io.lumify.web.routes.vertex;

import com.google.inject.Inject;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.ontology.OntologyProperty;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.ontology.PropertyType;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.user.User;
import io.lumify.core.util.ClientApiConverter;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.miniweb.HandlerChain;
import io.lumify.web.BaseRequestHandler;
import io.lumify.web.clientapi.model.VertexSearchResponse;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.securegraph.Authorizations;
import org.securegraph.Graph;
import org.securegraph.Vertex;
import org.securegraph.query.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.util.*;

public class VertexSearch extends BaseRequestHandler {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(VertexSearch.class);
    private final Graph graph;
    private final OntologyRepository ontologyRepository;
    private int defaultSearchResultCount;

    @Inject
    public VertexSearch(
            final OntologyRepository ontologyRepository,
            final Graph graph,
            final UserRepository userRepository,
            final Configuration configuration,
            final WorkspaceRepository workspaceRepository) {
        super(userRepository, workspaceRepository, configuration);
        this.ontologyRepository = ontologyRepository;
        this.graph = graph;
        defaultSearchResultCount = configuration.getInt(Configuration.DEFAULT_SEARCH_RESULT_COUNT, 100);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        long totalStartTime = System.nanoTime();
        final String query;
        final String filter = getRequiredParameter(request, "filter");
        final int offset = (int) getOptionalParameterLong(request, "offset", 0);
        final int size = (int) getOptionalParameterLong(request, "size", defaultSearchResultCount);
        final String conceptType = getOptionalParameter(request, "conceptType");
        final String getLeafNodes = getOptionalParameter(request, "leafNodes");
        final String relatedToVertexId = getOptionalParameter(request, "relatedToVertexId");
        if (relatedToVertexId == null) {
            query = getRequiredParameter(request, "q");
        } else {
            query = getOptionalParameter(request, "q");
        }

        long startTime = System.nanoTime();

        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        String workspaceId = getActiveWorkspaceId(request);

        JSONArray filterJson = new JSONArray(filter);

        ontologyRepository.resolvePropertyIds(filterJson);

        graph.flush();

        LOGGER.debug("search %s\n%s", query, filterJson.toString(2));

        Query graphQuery;
        if (relatedToVertexId == null) {
            graphQuery = graph.query(query, authorizations);
        } else if (query == null || StringUtils.isBlank(query)) {
            graphQuery = graph.getVertex(relatedToVertexId, authorizations).query(authorizations);
        } else {
            graphQuery = graph.getVertex(relatedToVertexId, authorizations).query(query, authorizations);
        }

        for (int i = 0; i < filterJson.length(); i++) {
            JSONObject obj = filterJson.getJSONObject(i);
            if (obj.length() > 0) {
                updateQueryWithFilter(graphQuery, obj);
            }
        }

        if (conceptType != null) {
            Concept concept = ontologyRepository.getConceptByIRI(conceptType);
            if (getLeafNodes == null || !getLeafNodes.equals("false")) {
                List<Concept> leafNodeList = ontologyRepository.getAllLeafNodesByConcept(concept);
                if (leafNodeList.size() > 0) {
                    String[] conceptIds = new String[leafNodeList.size()];
                    int count = 0;
                    for (Concept c : leafNodeList) {
                        conceptIds[count] = c.getTitle();
                        count++;
                    }
                    graphQuery.has(LumifyProperties.CONCEPT_TYPE.getPropertyName(), Compare.IN, conceptIds);
                }
            } else {
                graphQuery.has(LumifyProperties.CONCEPT_TYPE.getPropertyName(), conceptType);
            }
        }

        graphQuery.limit(size);
        graphQuery.skip(offset);
        Iterable<Vertex> searchResults;
        try {
            searchResults = graphQuery.vertices();
        } catch (SearchPhaseExecutionException ex) {
            respondWithBadRequest(response, "q", "Invalid Query");
            return;
        }

        Map<Object, Double> scores = null;
        if (searchResults instanceof IterableWithScores) {
            scores = ((IterableWithScores) searchResults).getScores();
        }

        long retrievalStartTime = System.nanoTime();
        List<io.lumify.web.clientapi.model.Vertex> verticesList = new ArrayList<io.lumify.web.clientapi.model.Vertex>();
        for (Vertex vertex : searchResults) {
            io.lumify.web.clientapi.model.Vertex v = ClientApiConverter.toClientApiVertex(vertex, workspaceId, authorizations);
            if (scores != null) {
                v.setScore(scores.get(vertex.getId()));
            }
            verticesList.add(v);
        }
        long retrievalEndTime = System.nanoTime();

        Collections.sort(verticesList, new Comparator<io.lumify.web.clientapi.model.Vertex>() {
            @Override
            public int compare(io.lumify.web.clientapi.model.Vertex o1, io.lumify.web.clientapi.model.Vertex o2) {
                double score1 = o1.getScore(0.0);
                double score2 = o2.getScore(0.0);
                return -Double.compare(score1, score2);
            }
        });

        long totalEndTime = System.nanoTime();

        VertexSearchResponse results = new VertexSearchResponse();
        results.getVertices().addAll(verticesList);
        results.setNextOffset(offset + size);
        results.setRetrievalTime(retrievalEndTime - retrievalStartTime);
        results.setTotalTime(totalEndTime - totalStartTime);

        if (searchResults instanceof IterableWithTotalHits) {
            results.setTotalHits(((IterableWithTotalHits) searchResults).getTotalHits());
        }
        if (searchResults instanceof IterableWithSearchTime) {
            results.setSearchTime(((IterableWithSearchTime) searchResults).getSearchTimeNanoSeconds());
        }

        long endTime = System.nanoTime();
        LOGGER.info("Search for \"%s\" found %d vertices in %dms", query, verticesList.size(), (endTime - startTime) / 1000 / 1000);

        respondWith(response, results);
    }

    private void updateQueryWithFilter(Query graphQuery, JSONObject obj) throws ParseException {
        String predicateString = obj.optString("predicate");
        JSONArray values = obj.getJSONArray("values");
        PropertyType propertyDataType = PropertyType.convert(obj.optString("propertyDataType"));
        String propertyName = obj.getString("propertyName");
        Object value0 = jsonValueToObject(values, propertyDataType, 0);

        if (PropertyType.STRING.equals(propertyDataType) && (predicateString == null || "".equals(predicateString))) {
            graphQuery.has(propertyName, TextPredicate.CONTAINS, value0);
        } else if (PropertyType.BOOLEAN.equals(propertyDataType) && (predicateString == null || "".equals(predicateString))) {
            graphQuery.has(propertyName, Compare.EQUAL, value0);
        } else if ("<".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.LESS_THAN, value0);
        } else if (">".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.GREATER_THAN, value0);
        } else if ("range".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.GREATER_THAN_EQUAL, value0);
            graphQuery.has(propertyName, Compare.LESS_THAN_EQUAL, jsonValueToObject(values, propertyDataType, 1));
        } else if ("=".equals(predicateString) || "equal".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.EQUAL, value0);
        } else if (PropertyType.GEO_LOCATION.equals(propertyDataType)) {
            graphQuery.has(propertyName, GeoCompare.WITHIN, value0);
        } else {
            throw new LumifyException("unhandled query\n" + obj.toString(2));
        }
    }

    private Object jsonValueToObject(JSONArray values, PropertyType propertyDataType, int index) throws ParseException {
        return OntologyProperty.convert(values, propertyDataType, index);
    }
}
