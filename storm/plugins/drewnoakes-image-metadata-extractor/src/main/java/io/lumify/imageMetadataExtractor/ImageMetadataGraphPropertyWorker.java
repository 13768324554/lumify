package io.lumify.imageMetadataExtractor;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkData;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorker;
import io.lumify.core.ingest.graphProperty.GraphPropertyWorkerPrepareData;
import io.lumify.core.model.audit.AuditAction;
import io.lumify.core.model.audit.AuditBuilder;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.imageMetadataHelper.*;
import io.lumify.storm.MediaPropertyConfiguration;
import io.lumify.storm.util.FileSizeUtil;
import org.securegraph.Element;
import org.securegraph.Property;
import org.securegraph.Vertex;
import org.securegraph.mutation.ExistingElementMutation;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class ImageMetadataGraphPropertyWorker extends GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(ImageMetadataGraphPropertyWorker.class);
    private static final String MULTI_VALUE_KEY = ImageMetadataGraphPropertyWorker.class.getName();
    private MediaPropertyConfiguration config = new MediaPropertyConfiguration();

    @Override
    public boolean isLocalFileRequired() {
        return true;
    }

    @Override
    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        super.prepare(workerPrepareData);
        getConfiguration().setConfigurables(config, MediaPropertyConfiguration.PROPERTY_NAME_PREFIX);
    }

    private void setProperty(String iri, Object value, ExistingElementMutation<Vertex> mutation, Map<String, Object> metadata, GraphPropertyWorkData data, List<String> properties) {
        if (iri != null && value != null) {
            mutation.addPropertyValue(MULTI_VALUE_KEY, iri, value, metadata, data.getVisibility());
            properties.add(iri);
        }
    }

    @Override
    public void execute(InputStream in, GraphPropertyWorkData data) throws Exception {
        Map<String, Object> metadata = data.createPropertyMetadata();
        ExistingElementMutation<Vertex> mutation = data.getElement().prepareMutation();
        List<String> properties = new ArrayList<String>();

        File imageFile = data.getLocalFile();
        if (imageFile != null) {
            Metadata imageMetadata = null;
            try {
                imageMetadata = ImageMetadataReader.readMetadata(imageFile);
            } catch (Exception e) {
                LOGGER.debug("Could not read metadata from imageFile.", e);
            }

            if (imageMetadata != null) {
                setProperty(config.dateTakenIri, DateExtractor.getDateDefault(imageMetadata), mutation, metadata, data, properties);
                setProperty(config.deviceMakeIri, MakeExtractor.getMake(imageMetadata), mutation, metadata, data, properties);
                setProperty(config.deviceModelIri, ModelExtractor.getModel(imageMetadata), mutation, metadata, data, properties);
                setProperty(config.geoLocationIri, GeoPointExtractor.getGeoPoint(imageMetadata), mutation, metadata, data, properties);
                setProperty(config.headingIri, HeadingExtractor.getImageHeading(imageMetadata), mutation, metadata, data, properties);
                setProperty(config.metadataIri, LeftoverMetadataExtractor.getAsJSON(imageMetadata).toString(), mutation, metadata, data, properties);
            }

            Integer width = imageMetadata != null ? DimensionsExtractor.getWidthViaMetadata(imageMetadata) : DimensionsExtractor.getWidthViaBufferedImage(imageFile);
            setProperty(config.widthIri, width, mutation, metadata, data, properties);

            Integer height = imageMetadata != null ? DimensionsExtractor.getHeightViaMetadata(imageMetadata) : DimensionsExtractor.getHeightViaBufferedImage(imageFile);
            setProperty(config.heightIri, height, mutation, metadata, data, properties);

            setProperty(config.fileSizeIri, FileSizeUtil.getSize(imageFile), mutation, metadata, data, properties);
        }

        Vertex v = mutation.save(getAuthorizations());
        // Auditing the new properties set and that this class analyzed the vertex
        new AuditBuilder()
                .auditAction(AuditAction.UPDATE)
                .user(getUser())
                .analyzedBy(getClass().getSimpleName())
                .vertexToAudit(v)
                .existingElementMutation(mutation)
                .auditExisitingVertexProperties(getAuthorizations())
                .auditAction(AuditAction.ANALYZED_BY)
                .auditVertex(getAuthorizations(), false);
        getGraph().flush();
        for (String propertyName : properties) {
            getWorkQueueRepository().pushGraphPropertyQueue(data.getElement(), MULTI_VALUE_KEY, propertyName);
        }
    }

    @Override
    public boolean isHandled(Element element, Property property) {
        if (property == null) {
            return false;
        }

        String mimeType = (String) property.getMetadata().get(LumifyProperties.MIME_TYPE.getPropertyName());
        if (mimeType != null && (mimeType.startsWith("image/jpeg") || mimeType.startsWith("image/tiff"))) {
            return true;
        }

        return false;
    }
}
