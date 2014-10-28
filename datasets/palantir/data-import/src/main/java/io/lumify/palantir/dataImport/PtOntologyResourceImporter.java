package io.lumify.palantir.dataImport;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import io.lumify.palantir.dataImport.model.PtOntologyResource;
import org.apache.commons.codec.binary.Base64;

public class PtOntologyResourceImporter extends PtImporterBase<PtOntologyResource> {

    protected PtOntologyResourceImporter(DataImporter dataImporter) {
        super(dataImporter, PtOntologyResource.class);
    }

    @Override
    protected void processRow(PtOntologyResource row) {
        String contentsBase64 = Base64.encodeBase64String(row.getContents());
        contentsBase64 = Joiner.on('\n').join(Splitter.fixedLength(76).split(contentsBase64));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" ?>\n");
        xml.append("<ontology_resource_config>\n");
        xml.append("  <type>").append(row.getType()).append("</type>\n");
        xml.append("  <path>").append(row.getPath()).append("</path>\n");
        xml.append("  <deleted>").append(row.isDeleted()).append("</deleted>\n");
        xml.append("  <contents>").append(contentsBase64).append("</contents>\n");
        xml.append("</ontology_resource_config>\n");

        getDataImporter().writeFile("image/OntologyResource" + row.getId() + ".xml", xml.toString());
    }

    @Override
    protected String getSql() {
        return "select * from {namespace}.PT_ONTOLOGY_RESOURCE";
    }
}
