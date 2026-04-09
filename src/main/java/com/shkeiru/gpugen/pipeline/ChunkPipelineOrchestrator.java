package com.shkeiru.gpugen.pipeline;

import com.shkeiru.gpugen.gpu.ComputeShaderManager;
import com.shkeiru.gpugen.gpu.GPUPacketDecoder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daemon central de l'orchestration GPU.
 */
public class ChunkPipelineOrchestrator {

    private static ChunkPipelineOrchestrator INSTANCE;

    public static synchronized ChunkPipelineOrchestrator getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ChunkPipelineOrchestrator();
        }
        return INSTANCE;
    }

    private record ChunkRequest(ChunkAccess chunk, CompletableFuture<ChunkAccess> future) {}

    private final ConcurrentLinkedQueue<ChunkRequest> pendingRequests = new ConcurrentLinkedQueue<>();
    private ExecutorService workerPool;
    
    // Le code JIT généré qui sera stocké et compilé dès le thread daemon allumé.
    private String dynamicShaderSource = null;
    private byte[] permutationData = null;

    private ChunkPipelineOrchestrator() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }

        this.workerPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GPU-Chunk-Pipeline");
            t.setDaemon(true);
            return t;
        });

        startPipeline();
    }
    
    public void provideDynamicShaderSource(String source, byte[] permutationData) {
        this.dynamicShaderSource = source;
        this.permutationData = permutationData;
    }

    private void startPipeline() {
        this.workerPool.submit(() -> {
            System.out.println("[GPU Pipeline] Initialisation du contexte Headless...");
            boolean initialized = ComputeShaderManager.getInstance().initHeadlessContext();
            
            if (!initialized) {
                System.err.println("[GPU Pipeline] Échec fatal de l'initialisation du contexte local.");
                return;
            }

            try {
                // Attente active sécurisée de bas niveau si la VM démarre plus vite que le JIT
                while (dynamicShaderSource == null && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10);
                }

                System.out.println("[GPU Pipeline] Injection de l'AST dans le compilateur GLSL...");
                ComputeShaderManager.getInstance().setupComputePipeline(dynamicShaderSource, permutationData);

                while (!Thread.currentThread().isInterrupted()) {
                    ChunkRequest request = pendingRequests.poll();
                    if (request != null) {
                        try {
                            processRequest(request);
                        } catch (Exception e) {
                            System.err.println("[GPU Pipeline] Crash JIT ou Routage VRAM: " + e.getMessage());
                            e.printStackTrace();
                            request.future().complete(request.chunk()); 
                        }
                    } else {
                        try {
                            Thread.sleep(1); 
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
            } finally {
                ComputeShaderManager.getInstance().cleanup();
                System.out.println("[GPU Pipeline] Contexte OpenGL détruit proprement.");
            }
        });
    }

    public CompletableFuture<ChunkAccess> queueChunkForGeneration(ChunkAccess chunkAccess) {
        if (!FMLEnvironment.dist.isClient()) {
            return CompletableFuture.completedFuture(chunkAccess);
        }

        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        pendingRequests.add(new ChunkRequest(chunkAccess, future));
        return future;
    }

    private void processRequest(ChunkRequest request) {
        ChunkAccess chunk = request.chunk();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        ComputeShaderManager.getInstance().dispatchChunkGeneration(chunkX, chunkZ);
        byte[] encodedData = ComputeShaderManager.getInstance().fetchAndFreeResult();
        GPUPacketDecoder.decodeAndApply(encodedData, chunk);

        request.future().complete(chunk);
    }
}
