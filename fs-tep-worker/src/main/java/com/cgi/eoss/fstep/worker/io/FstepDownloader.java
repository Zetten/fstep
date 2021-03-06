package com.cgi.eoss.fstep.worker.io;

import com.cgi.eoss.fstep.rpc.GetServiceContextFilesParams;
import com.cgi.eoss.fstep.rpc.ServiceContextFiles;
import com.cgi.eoss.fstep.rpc.ShortFile;
import com.cgi.eoss.fstep.rpc.catalogue.CatalogueServiceGrpc;
import com.cgi.eoss.fstep.rpc.catalogue.Databasket;
import com.cgi.eoss.fstep.rpc.catalogue.DatabasketContents;
import com.cgi.eoss.fstep.rpc.catalogue.FileResponse;
import com.cgi.eoss.fstep.rpc.catalogue.FstepFileUri;
import com.cgi.eoss.fstep.rpc.FstepServerClient;
import com.google.common.collect.ImmutableSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.GROUP_READ;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

@Component
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Log4j2
public class FstepDownloader implements Downloader {

    private static final EnumSet<PosixFilePermission> EXECUTABLE_PERMS = EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE, OTHERS_READ, OTHERS_EXECUTE);
    private static final EnumSet<PosixFilePermission> NON_EXECUTABLE_PERMS = EnumSet.of(OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ);

    private final FstepServerClient fstepServerClient;

    @Override
    public Set<String> getProtocols() {
        return ImmutableSet.of("fstep");
    }

    @Override
    public Path download(Path targetDir, URI uri) throws IOException {
        LOG.info("Downloading: {}", uri);

        switch (uri.getHost()) {
            case "serviceContext":
                return downloadServiceContextFiles(targetDir, Paths.get(uri.getPath()).getFileName().toString());
            case "outputProduct":
                return downloadFstepFile(targetDir, uri);
            case "refData":
                return downloadFstepFile(targetDir, uri);
            case "databasket":
                return downloadDatabasket(targetDir, uri);
            default:
                throw new UnsupportedOperationException("Unrecognised fstep:// URI type: " + uri.getHost());
        }
    }

    private Path downloadServiceContextFiles(Path targetDir, String serviceName) throws IOException {
        ServiceContextFiles serviceContextFiles = fstepServerClient.serviceContextFilesServiceBlockingStub()
                .getServiceContextFiles(GetServiceContextFilesParams.newBuilder().setServiceName(serviceName).build());

        for (ShortFile f : serviceContextFiles.getFilesList()) {
            Path targetFile = targetDir.resolve(f.getFilename());
            Set<PosixFilePermission> permissions = f.getExecutable() ? EXECUTABLE_PERMS : NON_EXECUTABLE_PERMS;

            LOG.debug("Writing service context file for {} to: {}", serviceName, targetFile);
            Files.write(targetFile, f.getContent().toByteArray(), CREATE, TRUNCATE_EXISTING);
            Files.setPosixFilePermissions(targetFile, permissions);
        }

        return targetDir;
    }

    private Path downloadFstepFile(Path targetDir, URI uri) throws IOException {
        CatalogueServiceGrpc.CatalogueServiceBlockingStub catalogueService = fstepServerClient.catalogueServiceBlockingStub();

        Iterator<FileResponse> outputFile = catalogueService.downloadFstepFile(FstepFileUri.newBuilder().setUri(uri.toString()).build());

        // First message is the file metadata
        FileResponse.FileMeta fileMeta = outputFile.next().getMeta();

        // TODO Configure whether files need to be transferred via RPC or simply copied
        Path outputPath = targetDir.resolve(fileMeta.getFilename());
        LOG.info("Transferring FstepFile ({} bytes) to {}", fileMeta.getSize(), outputPath);

        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(outputPath, CREATE, TRUNCATE_EXISTING, WRITE))) {
            outputFile.forEachRemaining(Unchecked.consumer(of -> of.getChunk().getData().writeTo(outputStream)));
        }

        return outputPath;
    }

    private Path downloadDatabasket(Path targetDir, URI uri) {
        CatalogueServiceGrpc.CatalogueServiceBlockingStub catalogueService = fstepServerClient.catalogueServiceBlockingStub();

        DatabasketContents databasketContents = catalogueService.getDatabasketContents(Databasket.newBuilder().setUri(uri.toASCIIString()).build());

        databasketContents.getFileUrisList().forEach(Unchecked.consumer((FstepFileUri fstepFileUri) -> {
            Path downloadedFile = downloadFstepFile(targetDir, URI.create(fstepFileUri.getUri()));
            LOG.info("Successfully downloaded file from databasket: {}", downloadedFile);
        }));

        return targetDir;
    }

}
