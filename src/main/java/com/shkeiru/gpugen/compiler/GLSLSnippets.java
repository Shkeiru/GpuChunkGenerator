package com.shkeiru.gpugen.compiler;

/**
 * Constantes, Stubs Mathématiques OpenGL et Headers du Shader JIT.
 * Phase 12 : Bruit fBm dynamiquement dimensionné et Seed globale.
 */
public class GLSLSnippets {

    public static final String COMPUTE_HEADER = """
        #version 430 core
        
        layout(local_size_x = 16, local_size_y = 1, local_size_z = 16) in;
        
        uniform int chunkX;
        uniform int chunkZ;
        
        layout(std430, binding = 1) writeonly buffer OutputBuffer {
            uint blocks[]; // Tableau linéaire des uints packés RLE
        };

        struct OctaveData {
            float xo;
            float yo;
            float zo;
            float padding;
            int p[256];
        };
        
        layout(std430, binding = 2) readonly buffer PermutationBuffer {
            OctaveData octaves[];
        };
        
        const int MIN_Y = -64;
        const int MAX_Y = 320;
        
        // --- Coordonnées Absolues du Contexte Spatial ---
        float global_x;
        float global_y;
        float global_z;
        
        """;

    public static final String PERLIN_NOISE_STUB = """
        float grad(int hash, float x, float y, float z) {
            int h = hash & 15;
            float u = h < 8 ? x : y;
            float v = h < 4 ? y : h == 12 || h == 14 ? x : z;
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        float fade(float t) {
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }

        float sample_improved_noise(vec3 pos, int octaveIndex) {
            float xo = octaves[octaveIndex].xo;
            float yo = octaves[octaveIndex].yo;
            float zo = octaves[octaveIndex].zo;
            
            float x = pos.x + xo;
            float y = pos.y + yo;
            float z = pos.z + zo;
            
            int X = int(floor(x)) & 255;
            int Y = int(floor(y)) & 255;
            int Z = int(floor(z)) & 255;

            x -= floor(x);
            y -= floor(y);
            z -= floor(z);

            float u = fade(x);
            float v = fade(y);
            float w = fade(z);

            int pX = octaves[octaveIndex].p[X];
            int pX1 = octaves[octaveIndex].p[(X + 1) & 255];
            
            int A  = pX + Y;
            int B  = pX1 + Y;

            int AA = octaves[octaveIndex].p[A & 255] + Z;
            int AB = octaves[octaveIndex].p[(A + 1) & 255] + Z;
            int BA = octaves[octaveIndex].p[B & 255] + Z;
            int BB = octaves[octaveIndex].p[(B + 1) & 255] + Z;

            return mix(
                mix(
                    mix(grad(octaves[octaveIndex].p[AA & 255], x, y, z), 
                        grad(octaves[octaveIndex].p[BA & 255], x - 1.0, y, z), u),
                    mix(grad(octaves[octaveIndex].p[AB & 255], x, y - 1.0, z), 
                        grad(octaves[octaveIndex].p[BB & 255], x - 1.0, y - 1.0, z), u),
                    v),
                mix(
                    mix(grad(octaves[octaveIndex].p[(AA + 1) & 255], x, y, z - 1.0), 
                        grad(octaves[octaveIndex].p[(BA + 1) & 255], x - 1.0, y, z - 1.0), u),
                    mix(grad(octaves[octaveIndex].p[(AB + 1) & 255], x, y - 1.0, z - 1.0), 
                        grad(octaves[octaveIndex].p[(BB + 1) & 255], x - 1.0, y - 1.0, z - 1.0), u),
                    v),
                w
            );
        }
        """;

    /**
     * Génère les surcharges de fonctions fBm pour les différentes tailles d'amplitudes.
     * Requis car GLSL 4.30 ne supporte pas les tableaux non dimensionnés en argument.
     */
    public static String getComputeHeaderAndNoiseBase() {
        StringBuilder sb = new StringBuilder();
        sb.append(COMPUTE_HEADER).append("\n");
        sb.append(PERLIN_NOISE_STUB).append("\n");
        
        for (int i = 1; i <= 16; i++) {
            sb.append("float mojang_double_perlin_noise_").append(i).append("(vec3 pos, float double_amplitude, int first_octave, int offset1, int offset2, float amps[").append(i).append("]) {\n");
            sb.append("    float first_val = 0.0;\n");
            sb.append("    float second_val = 0.0;\n");
            sb.append("    float freq1 = pow(2.0, float(first_octave));\n");
            sb.append("    float freq2 = freq1;\n");
            sb.append("    vec3 first_pos = pos * freq1;\n");
            sb.append("    vec3 second_pos = pos * 1.0181268882175227 * freq1;\n");
            sb.append("    float amp_seq = pow(2.0, ").append(i - 1).append(".0) / (pow(2.0, ").append(i).append(".0) - 1.0);\n");
            sb.append("    for (int i = 0; i < ").append(i).append("; i++) {\n");
            sb.append("        if (amps[i] != 0.0) {\n");
            sb.append("            first_val += amps[i] * amp_seq * sample_improved_noise(first_pos, offset1 + i);\n");
            sb.append("            second_val += amps[i] * amp_seq * sample_improved_noise(second_pos, offset2 + i);\n");
            sb.append("        }\n");
            sb.append("        first_pos *= 2.0;\n");
            sb.append("        second_pos *= 2.0;\n");
            sb.append("        amp_seq /= 2.0;\n");
            sb.append("    }\n");
            sb.append("    return (first_val + second_val) * double_amplitude;\n");
            sb.append("}\n\n");
        }
        
        return sb.toString();
    }
}
