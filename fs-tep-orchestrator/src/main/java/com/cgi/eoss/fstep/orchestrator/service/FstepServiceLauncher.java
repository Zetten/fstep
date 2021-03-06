package com.cgi.eoss.fstep.orchestrator.service;

import com.cgi.eoss.fstep.catalogue.CatalogueService;
import com.cgi.eoss.fstep.costing.CostingService;
import com.cgi.eoss.fstep.logging.Logging;
import com.cgi.eoss.fstep.model.FstepFile;
import com.cgi.eoss.fstep.model.FstepService;
import com.cgi.eoss.fstep.model.FstepServiceDescriptor;
import com.cgi.eoss.fstep.model.Job;
import com.cgi.eoss.fstep.model.JobConfig;
import com.cgi.eoss.fstep.model.JobStep;
import com.cgi.eoss.fstep.model.User;
import com.cgi.eoss.fstep.model.internal.OutputProductMetadata;
import com.cgi.eoss.fstep.persistence.service.JobDataService;
import com.cgi.eoss.fstep.rpc.FstepServiceLauncherGrpc;
import com.cgi.eoss.fstep.rpc.FstepServiceParams;
import com.cgi.eoss.fstep.rpc.FstepServiceResponse;
import com.cgi.eoss.fstep.rpc.GrpcUtil;
import com.cgi.eoss.fstep.rpc.JobParam;
import com.cgi.eoss.fstep.rpc.ListWorkersParams;
import com.cgi.eoss.fstep.rpc.WorkersList;
import com.cgi.eoss.fstep.rpc.worker.ContainerExitCode;
import com.cgi.eoss.fstep.rpc.worker.ExitParams;
import com.cgi.eoss.fstep.rpc.worker.ExitWithTimeoutParams;
import com.cgi.eoss.fstep.rpc.worker.FstepWorkerGrpc;
import com.cgi.eoss.fstep.rpc.worker.GetOutputFileParam;
import com.cgi.eoss.fstep.rpc.worker.JobDockerConfig;
import com.cgi.eoss.fstep.rpc.worker.JobEnvironment;
import com.cgi.eoss.fstep.rpc.worker.JobInputs;
import com.cgi.eoss.fstep.rpc.worker.LaunchContainerResponse;
import com.cgi.eoss.fstep.rpc.worker.ListOutputFilesParam;
import com.cgi.eoss.fstep.rpc.worker.OutputFileItem;
import com.cgi.eoss.fstep.rpc.worker.OutputFileList;
import com.cgi.eoss.fstep.rpc.worker.OutputFileResponse;
import com.cgi.eoss.fstep.security.FstepSecurityService;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.CloseableThreadContext;
import org.jooq.lambda.Unchecked;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.collect.Multimaps.toMultimap;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * <p>Primary entry point for WPS services to launch in FS-TEP.</p>
 * <p>Provides access to FS-TEP data services and job distribution capability.</p>
 */
@Service
@Log4j2
@GRpcService
public class FstepServiceLauncher extends FstepServiceLauncherGrpc.FstepServiceLauncherImplBase {

    private static final String TIMEOUT_PARAM = "timeout";

    private final WorkerFactory workerFactory;
    private final JobDataService jobDataService;
    private final FstepGuiServiceManager guiService;
    private final CatalogueService catalogueService;
    private final CostingService costingService;
    private final FstepSecurityService securityService;

    @Autowired
    public FstepServiceLauncher(WorkerFactory workerFactory, JobDataService jobDataService, FstepGuiServiceManager guiService, CatalogueService catalogueService, CostingService costingService, FstepSecurityService securityService) {
        this.workerFactory = workerFactory;
        this.jobDataService = jobDataService;
        this.guiService = guiService;
        this.catalogueService = catalogueService;
        this.costingService = costingService;
        this.securityService = securityService;
    }

    @Override
    public void launchService(FstepServiceParams request, StreamObserver<FstepServiceResponse> responseObserver) {
        String zooId = request.getJobId();
        String userId = request.getUserId();
        String serviceId = request.getServiceId();
        String jobConfigLabel = request.getJobConfigLabel();
        List<JobParam> rpcInputs = request.getInputsList();
        Multimap<String, String> inputs = GrpcUtil.paramsListToMap(rpcInputs);

        Job job = null;
        try (CloseableThreadContext.Instance ctc = CloseableThreadContext.push("FS-TEP Service Orchestrator")
                .put("userId", userId).put("serviceId", serviceId).put("zooId", zooId)) {
            // TODO Allow re-use of existing JobConfig
            job = jobDataService.buildNew(zooId, userId, serviceId, jobConfigLabel, inputs);
            com.cgi.eoss.fstep.rpc.Job rpcJob = createRpcJob(job);

            // Post back the job metadata for async responses
            responseObserver.onNext(FstepServiceResponse.newBuilder().setJob(rpcJob).build());

            ctc.put("jobId", String.valueOf(job.getId()));
            FstepService service = job.getConfig().getService();

            checkCost(job.getOwner(), job.getConfig());

            if (!checkInputs(job.getOwner(), rpcInputs)) {
                try (CloseableThreadContext.Instance userCtc = Logging.userLoggingContext()) {
                    LOG.error("User does not have read access to all requested inputs", userId);
                }
                throw new ServiceExecutionException("User does not have read access to all requested inputs");
            }

            FstepWorkerGrpc.FstepWorkerBlockingStub worker = workerFactory.getWorker(job.getConfig());

            SetMultimap<String, FstepFile> jobOutputFiles = MultimapBuilder.hashKeys().hashSetValues().build();

            if (service.getType() == FstepService.Type.PARALLEL_PROCESSOR) {
                LOG.info("Launching parallel processing for job {}", zooId);
                job.setStage(JobStep.PROCESSING.getText());
                jobDataService.save(job);

                // Split the magical "parallelInputs" attribute to get the individual job "input" parameters
                List<String> parallelInputs = inputs.get("parallelInputs").stream()
                        .map(i -> Arrays.asList(i.split(",")))
                        .flatMap(Collection::stream)
                        .collect(toList());

                // Create the simpler map of parameters shared by all parallel jobs
                SetMultimap<String, String> sharedParams = MultimapBuilder.hashKeys().hashSetValues().build(inputs);
                sharedParams.removeAll("parallelInputs");

                ExecutorService executorService = Executors.newCachedThreadPool();
                ListeningExecutorService listeningExecutorService = MoreExecutors.listeningDecorator(executorService);
                List<ListenableFuture<?>> jobFutures = new ArrayList<>(parallelInputs.size());

                for (String parallelInput : parallelInputs) {
                    SetMultimap<String, String> parallelJobParams = MultimapBuilder.hashKeys().hashSetValues().build(sharedParams);
                    parallelJobParams.put("input", parallelInput);

                    Job parallelJob = jobDataService.buildNew(UUID.randomUUID().toString(), userId, serviceId, jobConfigLabel, parallelJobParams);
                    com.cgi.eoss.fstep.rpc.Job parallelRpcJob = createRpcJob(parallelJob);
                    List<JobParam> parallelRpcInputs = GrpcUtil.mapToParams(parallelJobParams);

                    LOG.info("Launching child job {} ({}) for job {} ({})", parallelJob.getExtId(), parallelJob.getId(), job.getExtId(), job.getId());

                    jobFutures.add(listeningExecutorService.submit(Unchecked.runnable(() -> {
                        Map<String, FstepFile> parallelJobOutputs = executeJob(parallelJob, parallelRpcJob, parallelRpcInputs, worker);
                        parallelJobOutputs.forEach(jobOutputFiles::put);
                    })));
                }

                // Join and wait for the forked jobs
                LOG.info("Waiting for parallel child jobs to finish executing");
                Futures.allAsList(jobFutures).get(1, TimeUnit.DAYS);
                LOG.info("Parallel child jobs completed");

                // Wrap up the parent job
                job.setStatus(Job.Status.COMPLETED);
                job.setStage(JobStep.OUTPUT_LIST.getText());
                job.setEndTime(LocalDateTime.now());
                job.setGuiUrl(null);
                job.setOutputs(jobOutputFiles.entries().stream().collect(toMultimap(
                        e -> e.getKey(),
                        e -> e.getValue().getUri().toString(),
                        MultimapBuilder.hashKeys().hashSetValues()::build)));
                job.setOutputFiles(ImmutableSet.copyOf(jobOutputFiles.values()));
                jobDataService.save(job);
            } else {
                Map<String, FstepFile> jobOutputs = executeJob(job, rpcJob, rpcInputs, worker);
                jobOutputs.forEach(jobOutputFiles::put);
            }

            chargeUser(job.getOwner(), job);

            // Transform the results for the WPS response
            List<JobParam> outputs = jobOutputFiles.asMap().entrySet().stream()
                    .map(e -> JobParam.newBuilder().setParamName(e.getKey()).addAllParamValue(e.getValue().stream().map(f -> f.getUri().toASCIIString()).collect(toSet())).build())
                    .collect(toList());

            responseObserver.onNext(FstepServiceResponse.newBuilder()
                    .setJobOutputs(FstepServiceResponse.JobOutputs.newBuilder().addAllOutputs(outputs).build())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            if (job != null) {
                job.setStatus(Job.Status.ERROR);
                job.setEndTime(LocalDateTime.now());
                jobDataService.save(job);
            }

            LOG.error("Failed to run processor; notifying gRPC client", e);
            responseObserver.onError(new StatusRuntimeException(io.grpc.Status.fromCode(io.grpc.Status.Code.ABORTED).withCause(e)));
        }
    }

    private com.cgi.eoss.fstep.rpc.Job createRpcJob(Job job) {
        return com.cgi.eoss.fstep.rpc.Job.newBuilder()
                .setId(job.getExtId())
                .setIntJobId(String.valueOf(job.getId()))
                .setUserId(job.getOwner().getName())
                .setServiceId(job.getConfig().getService().getName())
                .build();
    }

    private Map<String, FstepFile> executeJob(Job job, com.cgi.eoss.fstep.rpc.Job rpcJob, List<JobParam> rpcInputs, FstepWorkerGrpc.FstepWorkerBlockingStub worker) throws IOException {
        String zooId = job.getExtId();
        String userId = job.getOwner().getName();
        FstepService service = job.getConfig().getService();
        Multimap<String, String> inputs = GrpcUtil.paramsListToMap(rpcInputs);

        // Prepare inputs
        LOG.info("Downloading input data for {}", zooId);
        job.setStartTime(LocalDateTime.now());
        job.setStatus(Job.Status.RUNNING);
        job.setStage(JobStep.DATA_FETCH.getText());
        jobDataService.save(job);

        JobEnvironment jobEnvironment = worker.prepareEnvironment(JobInputs.newBuilder()
                .setJob(rpcJob)
                .addAllInputs(rpcInputs)
                .build());

        // Configure container
        String dockerImageTag = service.getDockerTag();
        JobDockerConfig.Builder dockerConfigBuilder = JobDockerConfig.newBuilder()
                .setJob(rpcJob)
                .setServiceName(service.getName())
                .setDockerImage(dockerImageTag)
                .addBinds("/data:/data:ro")
                .addBinds(jobEnvironment.getWorkingDir() + "/FSTEP-WPS-INPUT.properties:" + "/home/worker/workDir/FSTEP-WPS-INPUT.properties:ro")
                .addBinds(jobEnvironment.getInputDir() + ":" + "/home/worker/workDir/inDir:ro")
                .addBinds(jobEnvironment.getOutputDir() + ":" + "/home/worker/workDir/outDir:rw");
        if (service.getType() == FstepService.Type.APPLICATION) {
            dockerConfigBuilder.addPorts(FstepGuiServiceManager.GUACAMOLE_PORT);
        }
        LOG.info("Launching docker container for job {}", zooId);
        job.setStage(JobStep.PROCESSING.getText());
        jobDataService.save(job);
        LaunchContainerResponse unused = worker.launchContainer(dockerConfigBuilder.build());

        // TODO Implement async service command execution

        LOG.info("Job {} ({}) launched for service: {}", job.getId(), zooId, service.getName());

        // Update GUI endpoint URL for client access
        if (service.getType() == FstepService.Type.APPLICATION) {
            String guiUrl = guiService.getGuiUrl(worker, rpcJob);
            LOG.info("Updating GUI URL for job {} ({}): {}", zooId, job.getConfig().getService().getName(), guiUrl);
            job.setGuiUrl(guiUrl);
            jobDataService.save(job);
        }

        // Wait for exit, with timeout if necessary
        ContainerExitCode exitCode;
        if (inputs.containsKey(TIMEOUT_PARAM)) {
            int timeout = Integer.parseInt(Iterables.getOnlyElement(inputs.get(TIMEOUT_PARAM)));
            exitCode = worker.waitForContainerExitWithTimeout(ExitWithTimeoutParams.newBuilder().setJob(rpcJob).setTimeout(timeout).build());
        } else {
            exitCode = worker.waitForContainerExit(ExitParams.newBuilder().setJob(rpcJob).build());
        }

        switch (exitCode.getExitCode()) {
            case 0:
                // Normal exit
                break;
            case 137:
                LOG.info("Docker container terminated via SIGKILL (exit code 137)");
                break;
            case 143:
                LOG.info("Docker container terminated via SIGTERM (exit code 143)");
                break;
            default:
                throw new ServiceExecutionException("Docker container returned with exit code " + exitCode);
        }

        job.setStage(JobStep.OUTPUT_LIST.getText());
        job.setEndTime(LocalDateTime.now()); // End time is when processing ends
        job.setGuiUrl(null); // Any GUI services will no longer be available
        jobDataService.save(job);

        // Repatriate output files

        // Enumerate files in the job output directory
        OutputFileList outputFileList = worker.listOutputFiles(ListOutputFilesParam.newBuilder()
                .setJob(rpcJob)
                .setOutputsRootPath(jobEnvironment.getOutputDir())
                .build());
        List<String> relativePaths = outputFileList.getItemsList().stream()
                .map(OutputFileItem::getRelativePath)
                .collect(Collectors.toList());

        Map<String, String> outputsByRelativePath;

        if (service.getType() == FstepService.Type.APPLICATION) {
            // Collect all files in the output directory with simple index IDs
            outputsByRelativePath = IntStream.range(0, relativePaths.size())
                    .boxed()
                    .collect(toMap(i -> Integer.toString(i + 1), relativePaths::get));
        } else {
            // Ensure we have one file per expected output
            Set<String> expectedServiceOutputIds = service.getServiceDescriptor().getDataOutputs().stream()
                    .map(FstepServiceDescriptor.Parameter::getId).collect(toSet());
            outputsByRelativePath = new HashMap<>(expectedServiceOutputIds.size());

            for (String expectedOutputId : expectedServiceOutputIds) {
                Optional<String> relativePath = relativePaths.stream()
                        .filter(path -> path.startsWith(expectedOutputId + "/"))
                        .reduce((a, b) -> null);
                if (relativePath.isPresent()) {
                    outputsByRelativePath.put(expectedOutputId, relativePath.get());
                } else {
                    throw new ServiceExecutionException(String.format("Did not find expected single output for '%s' in outputs list: %s", expectedOutputId, relativePaths));
                }
            }
        }

        Map<String, FstepFile> outputFiles = new HashMap<>(outputsByRelativePath.size());

        for (Map.Entry<String, String> output : outputsByRelativePath.entrySet()) {
            String outputId = output.getKey();
            String relativePath = output.getValue();

            Iterator<OutputFileResponse> outputFile = worker.getOutputFile(GetOutputFileParam.newBuilder()
                    .setJob(rpcJob)
                    .setPath(Paths.get(jobEnvironment.getOutputDir()).resolve(relativePath).toString())
                    .build());

            // First message is the file metadata
            OutputFileResponse.FileMeta fileMeta = outputFile.next().getMeta();
            LOG.info("Collecting output '{}' with filename {} ({} bytes)", outputId, fileMeta.getFilename(), fileMeta.getSize());

            OutputProductMetadata outputProduct = OutputProductMetadata.builder()
                    .owner(job.getOwner())
                    .service(service)
                    .jobId(zooId)
                    .crs(Iterables.getOnlyElement(inputs.get("crs"), null))
                    .geometry(Iterables.getOnlyElement(inputs.get("aoi"), null))
                    .properties(new HashMap<>(ImmutableMap.<String, Object>builder()
                            .put("jobId", zooId)
                            .put("intJobId", job.getId())
                            .put("serviceName", service.getName())
                            .put("jobOwner", job.getOwner().getName())
                            .put("jobStartTime", job.getStartTime().atOffset(ZoneOffset.UTC).toString())
                            .put("jobEndTime", job.getEndTime().atOffset(ZoneOffset.UTC).toString())
                            .put("filename", fileMeta.getFilename())
                            .build()))
                    .build();

            // TODO Configure whether files need to be transferred via RPC or simply copied
            Path outputPath = catalogueService.provisionNewOutputProduct(outputProduct, fileMeta.getFilename());
            LOG.info("Writing output file for job {}: {}", zooId, outputPath);
            try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(outputPath, CREATE, TRUNCATE_EXISTING, WRITE))) {
                outputFile.forEachRemaining(Unchecked.consumer(of -> of.getChunk().getData().writeTo(outputStream)));
            }

            outputFiles.put(outputId, catalogueService.ingestOutputProduct(outputProduct, outputPath));
        }

        job.setStatus(Job.Status.COMPLETED);
        job.setOutputs(outputFiles.entrySet().stream().collect(toMultimap(
                e -> e.getKey(),
                e -> e.getValue().getUri().toString(),
                MultimapBuilder.hashKeys().hashSetValues()::build)));
        job.setOutputFiles(ImmutableSet.copyOf(outputFiles.values()));
        jobDataService.save(job);

        if (service.getType() == FstepService.Type.BULK_PROCESSOR) {
            // Auto-publish the output files
            ImmutableSet.copyOf(outputFiles.values()).forEach(f -> securityService.publish(FstepFile.class, f.getId()));
        }

        return outputFiles;
    }

    private boolean checkInputs(User user, List<JobParam> inputsList) {
        Multimap<String, String> inputs = GrpcUtil.paramsListToMap(inputsList);

        Set<URI> inputUris = inputs.entries().stream()
                .filter(e -> this.isValidUri(e.getValue()))
                .flatMap(e -> Arrays.stream(StringUtils.split(e.getValue(), ',')).map(URI::create))
                .collect(toSet());

        return inputUris.stream().allMatch(uri -> catalogueService.canUserRead(user, uri));
    }

    @Override
    public void listWorkers(ListWorkersParams request, StreamObserver<WorkersList> responseObserver) {
        try {
            responseObserver.onNext(workerFactory.listWorkers());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("Failed to enumerate workers", e);
            responseObserver.onError(new StatusRuntimeException(io.grpc.Status.fromCode(io.grpc.Status.Code.ABORTED).withCause(e)));
        }
    }

    private void checkCost(User user, JobConfig jobConfig) {
        int estimatedCost = costingService.estimateJobCost(jobConfig);
        if (estimatedCost > user.getWallet().getBalance()) {
            throw new ServiceExecutionException("Estimated cost (" + estimatedCost + " coins) exceeds current wallet balance");
        }
        // TODO Should estimated balance be "locked" in the wallet?
    }

    private void chargeUser(User user, Job job) {
        costingService.chargeForJob(user.getWallet(), job);
    }

    private boolean isValidUri(String test) {
        try {
            return URI.create(test).getScheme() != null;
        } catch (Exception unused) {
            return false;
        }
    }

}
