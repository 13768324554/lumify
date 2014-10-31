package io.lumify.analystsNotebook;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.lumify.analystsNotebook.aggregateClassification.AggregateClassificationClient;
import io.lumify.analystsNotebook.model.*;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.formula.FormulaEvaluator;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.workspace.Workspace;
import io.lumify.core.model.workspace.WorkspaceEntity;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.securegraph.Authorizations;
import org.securegraph.Edge;
import org.securegraph.Graph;
import org.securegraph.Vertex;
import org.securegraph.util.LookAheadIterable;

import java.util.*;

import static org.securegraph.util.IterableUtils.toList;

@Singleton
public class AnalystsNotebookExporter {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(AnalystsNotebookExporter.class);
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String XML_COMMENT_START = "<!-- ";
    private static final String XML_COMMENT_END = " -->";
    private static final String XML_COMMENT_INDENT = "     ";
    private static final String LINE_SEPARATOR = System.getProperty("line.separator");
    private Graph graph;
    private WorkspaceRepository workspaceRepository;
    private OntologyRepository ontologyRepository;
    private Configuration configuration;
    private AggregateClassificationClient aggregateClassificationClient;

    @Inject
    public AnalystsNotebookExporter(Graph graph,
                                    WorkspaceRepository workspaceRepository,
                                    OntologyRepository ontologyRepository,
                                    Configuration configuration) {
        this.graph = graph;
        this.workspaceRepository = workspaceRepository;
        this.ontologyRepository = ontologyRepository;
        this.configuration = configuration;
        aggregateClassificationClient = new AggregateClassificationClient(configuration);
    }

    public static String toXml(Chart chart, List<String> comments) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(XML_DECLARATION).append(LINE_SEPARATOR);
            sb = appendComments(sb, comments);
            sb.append(getXmlMapper().writeValueAsString(chart));
            return sb.toString();
        } catch (JsonProcessingException e) {
            throw new LumifyException("exception while generating XML", e);
        }
    }

    public Chart toChart(AnalystsNotebookVersion version, Workspace workspace, User user, Authorizations authorizations, Locale locale, String timeZone) {
        LOGGER.debug("creating Chart from workspace %s for Analyst's Notebook version %s", workspace.getWorkspaceId(), version.toString());

        List<WorkspaceEntity> workspaceEntities = workspaceRepository.findEntities(workspace, user);

        Iterable<String> vertexIds = getVisibleWorkspaceEntityIds(workspaceEntities);
        Iterable<Vertex> vertices = graph.getVertices(vertexIds, authorizations);
        Map<Vertex, WorkspaceEntity> vertexWorkspaceEntityMap = createVertexWorkspaceEntityMap(vertices, workspaceEntities);

        List<Edge> edges = toList(graph.getEdges(graph.findRelatedEdges(vertexIds, authorizations), authorizations));

        String classificationBanner = aggregateClassificationClient.getClassificationBanner(vertices);

        Chart chart = new Chart();

        chart.setLinkTypeCollection(getLinkTypes());

        chart.setEntityTypeCollection(EntityType.createForVertices(vertices, ontologyRepository));

        List<ChartItem> chartItems = new ArrayList<ChartItem>();
        LOGGER.debug("adding %d vertices", vertexWorkspaceEntityMap.size());

        FormulaEvaluator formulaEvaluator = new FormulaEvaluator(configuration, ontologyRepository, locale, timeZone);
        for (Map.Entry<Vertex, WorkspaceEntity> entry : vertexWorkspaceEntityMap.entrySet()) {
            chartItems.add(ChartItem.createFromVertexAndWorkspaceEntity(version, entry.getKey(), entry.getValue(), ontologyRepository, formulaEvaluator, workspace.getWorkspaceId(), authorizations));
        }
        formulaEvaluator.close();

        LOGGER.debug("adding %d edges", edges.size());
        for (Edge edge : edges) {
            chartItems.add(ChartItem.createFromEdge(version, edge, ontologyRepository));
        }
        if (classificationBanner != null) {
            // TODO: select x,y
            chartItems.add(getLabelChartItem(classificationBanner, 4889, 7, "class_header"));
            chartItems.add(getLabelChartItem(classificationBanner, 4889, 6667, "class_footer"));
        }
        chart.setChartItemCollection(chartItems);

        if (version == AnalystsNotebookVersion.VERSION_7_OR_8 && classificationBanner != null) {
            chart.setSummary(getSummary(classificationBanner));
            chart.setPrintSettings(getPrintSettings());
        }

        return chart;
    }

    private ChartItem getLabelChartItem(String chartItemLabelAndDescription, int x, int y, String labelId) {
        ChartItem chartItem = new ChartItem();
        chartItem.setLabel(chartItemLabelAndDescription);
        chartItem.setDescription(chartItemLabelAndDescription);
        chartItem.setDateSet(false);
        chartItem.setxPosition(x);
        Label label = new Label();
        label.setLabelId(labelId);
        End end = new End();
        end.setY(y);
        end.setLabel(label);
        chartItem.setEnd(end);
        return chartItem;
    }

    private Map<Vertex, WorkspaceEntity> createVertexWorkspaceEntityMap(Iterable<Vertex> vertices, List<WorkspaceEntity> workspaceEntities) {
        Map<Vertex, WorkspaceEntity> map = new HashMap<Vertex, WorkspaceEntity>();
        for (Vertex vertex : vertices) {
            WorkspaceEntity correspondingWorkspaceEntity = null;
            for (WorkspaceEntity workspaceEntity : workspaceEntities) {
                if (workspaceEntity.getEntityVertexId().equals(vertex.getId())) {
                    correspondingWorkspaceEntity = workspaceEntity;
                    break;
                }
            }
            if (correspondingWorkspaceEntity != null) {
                map.put(vertex, correspondingWorkspaceEntity);
            }
        }
        return map;
    }

    private List<LinkType> getLinkTypes() {
        List<LinkType> linkTypes = new ArrayList<LinkType>();
        LinkType linkType = new LinkType();
        linkType.setColour("65280");
        linkType.setName(LinkType.NAME_LINK);
        linkTypes.add(linkType);
        return linkTypes;
    }

    private PrintSettings getPrintSettings() {
        PrintSettings printSettings = new PrintSettings();
        List<Header> headers = new ArrayList<Header>();
        Header header = new Header();
        header.setPosition(Header.POSITION_HEADER_FOOTER_POSITION_CENTER);
        header.setProperty("classification");
        header.setVisible(true);
        headers.add(header);
        printSettings.setHeaderCollection(headers);
        List<Footer> footers = new ArrayList<Footer>();
        Footer footer = new Footer();
        footer.setPosition(Footer.POSITION_HEADER_FOOTER_POSITION_CENTER);
        footer.setProperty("classification");
        footer.setVisible(true);
        footers.add(footer);
        printSettings.setFooterCollection(footers);
        return printSettings;
    }

    private Summary getSummary(String classificationBanner) {
        Summary summary = new Summary();
        List<CustomProperty> customProperties = new ArrayList<CustomProperty>();
        CustomProperty customProperty = new CustomProperty();
        customProperty.setName("classification");
        customProperty.setType(CustomProperty.TYPE_STRING);
        customProperty.setValue(classificationBanner);
        customProperties.add(customProperty);
        summary.setCustomPropertyCollection(customProperties);
        return summary;
    }

    private static XmlMapper getXmlMapper() {
        XmlMapper mapper = new XmlMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.PASCAL_CASE_TO_CAMEL_CASE);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    private static StringBuilder appendComments(StringBuilder sb, List<String> comments) {
        if (comments != null && comments.size() > 0) {
            if (comments.size() == 1) {
                return sb.append(XML_COMMENT_START).append(comments.get(0)).append(XML_COMMENT_END).append(LINE_SEPARATOR);
            } else {
                for (int i = 0; i < comments.size(); i++) {
                    sb.append(i == 0 ? XML_COMMENT_START : XML_COMMENT_INDENT).append(comments.get(i)).append(LINE_SEPARATOR);
                }
                sb.append(XML_COMMENT_END).append(LINE_SEPARATOR);
            }
        }
        return sb;
    }

    // TODO: this is copied from io.lumify.web.routes.workspace.WorkspaceVertices
    private LookAheadIterable<WorkspaceEntity, String> getVisibleWorkspaceEntityIds(final List<WorkspaceEntity> workspaceEntities) {
        return new LookAheadIterable<WorkspaceEntity, String>() {
            @Override
            protected boolean isIncluded(WorkspaceEntity workspaceEntity, String entityVertexId) {
                return workspaceEntity.isVisible();
            }

            @Override
            protected String convert(WorkspaceEntity workspaceEntity) {
                return workspaceEntity.getEntityVertexId();
            }

            @Override
            protected Iterator<WorkspaceEntity> createIterator() {
                return workspaceEntities.iterator();
            }
        };
    }
}
