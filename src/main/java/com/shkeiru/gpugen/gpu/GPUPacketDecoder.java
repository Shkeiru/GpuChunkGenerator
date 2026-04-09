package com.shkeiru.gpugen.gpu;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Décodeur RLE (Run-Length Encoding) pur CPU.
 * Rollback de sécurité : utilise l'API standard chunk.setBlockState()
 * sur ProtoChunk (pas de triggers de lumière ni de Heightmap manuels nécessaires).
 */
public class GPUPacketDecoder {

    // Cache Statique des BlockStates pour ignorer le coût de 'defaultBlockState()'
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public static void decodeAndApply(byte[] encodedData, ChunkAccess chunk) {
        ByteBuffer javaBuffer = ByteBuffer.wrap(encodedData).order(ByteOrder.nativeOrder());
        IntBuffer intBuffer = javaBuffer.asIntBuffer();
        
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight();
        
        // Un seul MutableBlockPos pour toute la durée du décodage (0 allocation GC)
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                
                int columnOffset = (z * 16 + x) * 384;
                
                int yCursor = minY;
                int readIndex = columnOffset;
                
                while (yCursor < maxY) {
                    int packedData = intBuffer.get(readIndex++);
                    
                    if (packedData == 0) {
                        break; // EOF Colonne RLE
                    }
                    
                    int count = packedData >>> 16;
                    int blockId = packedData & 0xFFFF;
                    
                    BlockState fillState = AIR;
                    
                    if (blockId == 1) {
                        fillState = STONE;
                    } else if (blockId == 2) {
                        fillState = WATER;
                    }
                    
                    // Remplissage via API standard ProtoChunk (Heightmaps + Light gérés nativement)
                    for (int dy = 0; dy < count && yCursor < maxY; dy++) {
                        pos.set(x, yCursor, z);
                        chunk.setBlockState(pos, fillState, false);
                        yCursor++;
                    }
                }
            }
        }
    }
}
