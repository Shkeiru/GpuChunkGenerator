package com.shkeiru.gpugen.mixin;

import com.shkeiru.gpugen.compiler.DensityToGLSLTranspiler;
import com.shkeiru.gpugen.pipeline.ChunkPipelineOrchestrator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.fml.loading.FMLEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Le noyau de Piratage de Génération. (Vampirisation Vanilla)
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class MixinNoiseBasedChunkGenerator {

    private static boolean gpuTranspilerInitialized = false;

    /**
     * INJECTION : Interception Vectorielle et Déclenchement Lazy (Lazy-Load JIT)
     */
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void onFillFromNoise(
            net.minecraft.world.level.levelgen.blending.Blender blender,
            net.minecraft.world.level.levelgen.RandomState randomState,
            net.minecraft.world.level.StructureManager structureManager,
            ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
            
        if (!FMLEnvironment.dist.isClient()) return;

        // --- PHASE 1 : Initialisation JIT (Retardée pour éviter le crash de l'Unbound Registry au Bootstrap) ---
        synchronized (MixinNoiseBasedChunkGenerator.class) {
            if (!gpuTranspilerInitialized) {
                System.out.println("[GPU Mixin] Interception Initiale LAZY (Zero-Config)...");
                System.out.println("[GPU Mixin] Traduction de l'AST du Datapack courant vers GLSL On-The-Fly !");
                
                // L'AST est extrait depuis le RandomState qui contient les instances ancrées sur la Seed du monde.
                var finalDensityProvider = randomState.router().finalDensity();
                int minY = chunk.getMinBuildHeight();
                int height = chunk.getHeight();
                
                DensityToGLSLTranspiler transpiler = new DensityToGLSLTranspiler(minY, height);
                String glslSource = transpiler.compileShaderSource(finalDensityProvider);
                
                byte[] permutationData = transpiler.getPermutationData();
                ChunkPipelineOrchestrator.getInstance().provideDynamicShaderSource(glslSource, permutationData);
                
                gpuTranspilerInitialized = true;
                System.out.println("[GPU Mixin] Orchestrateur prêt !");
            }
        }

        // --- PHASE 2 : Le Détournement de Génération ---
        
        // Le Flux CPU Vanilla est oblitéré d'un seul coup.
        CompletableFuture<ChunkAccess> gpuFuture = ChunkPipelineOrchestrator.getInstance().queueChunkForGeneration(chunk);
        
        cir.setReturnValue(gpuFuture);
    }
}
