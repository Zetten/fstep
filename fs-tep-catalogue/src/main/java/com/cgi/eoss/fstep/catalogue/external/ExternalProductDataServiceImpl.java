package com.cgi.eoss.fstep.catalogue.external;

import com.cgi.eoss.fstep.catalogue.CatalogueUri;
import com.cgi.eoss.fstep.catalogue.resto.RestoService;
import com.cgi.eoss.fstep.model.FstepFile;
import com.cgi.eoss.fstep.persistence.service.FstepFileDataService;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.geojson.Feature;
import org.geojson.GeoJsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <p>Handler for external product (e.g. S-1, S-2, Landsat) metadata and files.</p>
 */
@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class ExternalProductDataServiceImpl implements ExternalProductDataService {

    private final FstepFileDataService fstepFileDataService;
    private final RestoService resto;

    @Override
    public FstepFile ingest(GeoJsonObject geoJson) {
        // TODO Handle non-feature objects?
        Feature feature = (Feature) geoJson;

        String productSource = feature.getProperty("productSource").toString().toLowerCase().replaceAll("[^a-z0-9]", "");
        String productId = feature.getProperty("productIdentifier");

        Long filesize = getFilesize(feature);
        feature.getProperties().put("filesize", filesize);

        URI uri = getUri(productSource, productId);
        feature.getProperties().put("fstepUrl", uri);

        return Optional.ofNullable(fstepFileDataService.getByUri(uri)).orElseGet(() -> {
            UUID restoId;
            try {
                restoId = resto.ingestExternalProduct(productSource, feature);
                LOG.info("Ingested external product with Resto id {} and URI {}", restoId, uri);
            } catch (Exception e) {
                LOG.error("Failed to ingest external product to Resto, continuing...", e);
                // TODO Add GeoJSON to FstepFile model
                restoId = UUID.randomUUID();
            }
            FstepFile fstepFile = new FstepFile(uri, restoId);
            fstepFile.setType(FstepFile.Type.EXTERNAL_PRODUCT);
            fstepFile.setFilename(productId);
            fstepFile.setFilesize(filesize);
            return fstepFileDataService.save(fstepFile);
        });
    }

    private URI getUri(String productSource, String productId) {
        URI uri;
        try {
            CatalogueUri productSourceUrl = CatalogueUri.valueOf(productSource);
            uri = productSourceUrl.build(ImmutableMap.of("productId", productId));
        } catch (IllegalArgumentException e) {
            uri = URI.create(productSource.replaceAll("[^a-z0-9+.-]", "-") + ":///" + productId);
            LOG.debug("Could not build a well-designed FS-TEP URI handler, returning automatic: {}", uri, e);
        }
        return uri;
    }

    private Long getFilesize(Feature feature) {
        Long filesize = null;
        Map<String, Object> extraParams = feature.getProperties().containsKey("extraParams") ? feature.getProperty("extraParams") : ImmutableMap.of();
        if (extraParams.containsKey("file")) {
            Map<String, Object> fileProperties = (Map<String, Object>) extraParams.get("file");
            filesize = fileProperties.containsKey("data_file_size") ? Long.parseLong(fileProperties.get("data_file_size").toString()) : null;
        }
        return filesize;
    }

    @Override
    public Resource resolve(FstepFile file) {
        // TODO Allow proxied access (with TEP coin cost) to some external data
        throw new UnsupportedOperationException("Direct download of external products via FS-TEP is not permitted");
    }

    @Override
    public void delete(FstepFile file) throws IOException {
        resto.deleteReferenceData(file.getRestoId());
    }

}
