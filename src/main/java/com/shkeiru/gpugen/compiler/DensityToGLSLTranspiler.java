package com.shkeiru.gpugen.compiler;

import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transpilateur JIT — Inline Expression Tree + Cache Hoisting 2D + fBm Réel.
 * Phase 11 : Les nœuds de cache Mojang (FlatCache, Cache2D) sont hoistés avant la boucle Y.
 */
public class DensityToGLSLTranspiler {

    private int splineIndex = 0;
    private int noiseIndex = 0;
    private int cacheCounter = 0;
    private int varCounter = 0;
    private boolean inCache2D = false;
    
    private final StringBuilder globalScopeBuilder = new StringBuilder();
    
    // Buffer 2D : code GLSL injecté AVANT la boucle Y (hoisting)
    private final StringBuilder setup2DSnippets = new StringBuilder();
    
    // Buffer 3D : code GLSL injecté DANS la boucle Y
    private final StringBuilder loop3DSnippets = new StringBuilder();
    
    // Pour les Permutations GPU :
    private final Map<Object, NoiseInfo> noiseRegistry = new IdentityHashMap<>();
    private final java.util.List<byte[]> globalPermutationsList = new java.util.ArrayList<>();
    private int currentOctaveOffset = 0;
    
    // Cache DAG : évite de recalculer un sous-arbre identique (ref identity)
    private final Map<DensityFunction, String> evaluatedNodes = new IdentityHashMap<>();

    public DensityToGLSLTranspiler() { }

    public String compileShaderSource(DensityFunction root) {
        String inlinedExpression = visit(root);
        
        StringBuilder fullShader = new StringBuilder();
        fullShader.append(GLSLSnippets.getComputeHeaderAndNoiseBase());
        
        fullShader.append("\n// --- SCOPE GLOBAL (SPLINES JIT) ---\n");
        fullShader.append(globalScopeBuilder.toString());
        
        fullShader.append("""
        void main() {
            int localX = int(gl_LocalInvocationID.x);
            int localZ = int(gl_LocalInvocationID.z);
            
            global_x = float((chunkX * 16) + localX);
            global_z = float((chunkZ * 16) + localZ);
            
            // === CACHE HOISTING 2D (calculé UNE SEULE FOIS par colonne) ===
            global_y = 0.0; // Valeur neutre pour les évaluations 2D
        """);
        
        // Injection du buffer 2D pré-calculé
        fullShader.append(setup2DSnippets.toString());
        
        fullShader.append("""
            
            // --- CONTEXTE DE COMPRESSION RLE 1D ---
            uint current_id = 0u;
            uint run_count = 0u;
            uint write_offset = uint((localZ * 16 + localX) * 384);
            
            for (int y_iter = MIN_Y; y_iter < MAX_Y; y_iter++) {
                global_y = float(y_iter);
                
                // === EXPRESSION 3D INLINÉE (réutilise les registres 2D hoistés) ===
        """);
        
        fullShader.append(loop3DSnippets.toString());
        fullShader.append("        float final_density = ").append(inlinedExpression).append(";\n");
        
        fullShader.append("""
                // === FIN AST ===
                
                uint block_id = 0u; // Air
                if (final_density > 0.0) {
                    block_id = 1u; // Roche
                } else if (y_iter < 63) {
                    block_id = 2u; // Eau
                }
                
                if (y_iter == MIN_Y) {
                    current_id = block_id;
                    run_count = 1u;
                } else {
                    if (block_id == current_id) {
                        run_count++;
                    } else {
                        blocks[write_offset] = (run_count << 16) | (current_id & 0xFFFFu);
                        write_offset++;
                        current_id = block_id;
                        run_count = 1u;
                    }
                }
            }
            
            blocks[write_offset] = (run_count << 16) | (current_id & 0xFFFFu);
            blocks[write_offset + 1] = 0u;
        }
        """);
        
        return fullShader.toString();
    }

    // ==================== VISITEUR PRINCIPAL ====================

    private String visit(DensityFunction node) {
        String typeName = getSimpleName(node);
        System.out.println("[GPU DEBUG] visit() -> " + typeName);
        if (evaluatedNodes.containsKey(node)) {
            return evaluatedNodes.get(node);
        }
        String result;
        
        try {
            result = switch (typeName) {
                // --- STRUCTURELS (Pass-Through) ---
                case "HolderHolder"       -> visit(unwrapHolderHolder(node));
                case "Marker"             -> visit(unwrapMarker(node));
                case "Interpolated"       -> visit(unwrapMarker(node));
                case "BlendDensity"       -> visit(unwrapSingleArg(node));
                case "BlendAlpha"         -> "1.0";
                case "BlendOffset"        -> "0.0";
                case "WeirdScaledSampler" -> visit(unwrapSingleArg(node));
                
                // --- CACHE HOISTING (élevé avant la boucle Y) ---
                case "FlatCache"       -> hoistCache2D(node);
                case "Cache2D"         -> hoistCache2D(node);
                case "CacheOnce"       -> hoistCache2D(node);
                case "CacheAllInCell"  -> hoistCache2D(node);
                
                // --- MATHÉMATIQUES (Inline strict) ---
                case "Constant"         -> formatDouble(getDouble(node, "value"));
                case "Add"              -> "(" + visit(getFunc(node, "argument1")) + " + " + visit(getFunc(node, "argument2")) + ")";
                case "Mul"              -> "(" + visit(getFunc(node, "argument1")) + " * " + visit(getFunc(node, "argument2")) + ")";
                case "Clamp"            -> "clamp(" + visit(getFunc(node, "input")) + ", " + formatDouble(getDouble(node, "minValue")) + ", " + formatDouble(getDouble(node, "maxValue")) + ")";
                case "MulOrAdd"         -> "(" + visit(getFunc(node, "argument1")) + " * " + visit(getFunc(node, "argument2")) + " + " + formatDouble(getDouble(node, "argument3")) + ")";
                
                case "Ap2"              -> visitAp2(node);
                case "Mapped"           -> visitMapped(node);
                case "RangeChoice"      -> visitRangeChoice(node);
                case "YClampedGradient" -> visitYClampedGradient(node);
                case "Spline"           -> visitSpline(node);
                
                // --- NOISE (fBm à Octaves Réelles) ---
                case "Noise"            -> visitNoise(node);
                case "ShiftedNoise"     -> visitShiftedNoise(node);
                case "ShiftA"           -> visitShiftA(node);
                case "ShiftB"           -> visitShiftB(node);
                case "Shift"            -> visitShift(node);
                case "BlendedNoise"     -> visitBlendedNoise(node);
                
                default -> {
                    System.err.println("[GPU JIT] Noeud inconnu bypassé: " + node.getClass().getName());
                    yield "0.0";
                }
            };
        } catch (Exception e) {
            System.err.println("[GPU JIT] Erreur reflection sur " + typeName + ": " + e.getMessage());
            result = "0.0";
        }

        if (typeName.equals("Constant") || result.equals("0.0") || result.equals("1.0")) {
            evaluatedNodes.put(node, result);
            return result;
        }

        String varName = (inCache2D ? "var2D_" : "var3D_") + (++varCounter);
        
        if (inCache2D) {
            globalScopeBuilder.append("    float ").append(varName).append(";\n");
            setup2DSnippets.append("    ").append(varName).append(" = ").append(result).append(";\n");
        } else {
            loop3DSnippets.append("        float ").append(varName).append(" = ").append(result).append(";\n");
        }

        evaluatedNodes.put(node, varName);
        return varName;
    }
    
    // ==================== CACHE HOISTING ====================
    
    /**
     * Élève un nœud de cache 2D hors de la boucle Y.
     * Le sous-arbre complet est évalué une seule fois par colonne (x, z),
     * stocké dans un registre local, et réutilisé 384 fois sans recalcul.
     */
    private String hoistCache2D(DensityFunction node) throws Exception {
        boolean prev = inCache2D;
        inCache2D = true;
        
        DensityFunction inner = unwrapMarker(node);
        String evaluatedGLSL = visit(inner);
        
        inCache2D = prev;
        
        // evaluatedGLSL IS ALREADY a generated var2D identifier because visit() processed it in Cache2D.
        return evaluatedGLSL;
    }
    
    // ==================== VISITEURS SPÉCIALISÉS ====================
    
    private String visitAp2(DensityFunction node) throws Exception {
        Object typeEnum = getObj(node, "type");
        String opName = typeEnum != null ? typeEnum.toString() : "ADD";
        String left = visit(getFunc(node, "argument1"));
        String right = visit(getFunc(node, "argument2"));
        
        return switch (opName) {
            case "ADD" -> "(" + left + " + " + right + ")";
            case "MUL" -> "(" + left + " * " + right + ")";
            case "MIN" -> "min(" + left + ", " + right + ")";
            case "MAX" -> "max(" + left + ", " + right + ")";
            default    -> "(" + left + " + " + right + ")";
        };
    }
    
    private String visitMapped(DensityFunction node) throws Exception {
        String input = visit(getFunc(node, "input"));
        Object typeEnum = getObj(node, "type");
        String mapType = typeEnum != null ? typeEnum.toString() : "UNKNOWN";
        
        return switch (mapType) {
            case "ABS"              -> "abs(" + input + ")";
            case "SQUARE"           -> "(" + input + " * " + input + ")";
            case "CUBE"             -> "(" + input + " * " + input + " * " + input + ")";
            case "HALF_NEGATIVE"    -> "(" + input + " > 0.0 ? " + input + " : " + input + " * 0.5)";
            case "QUARTER_NEGATIVE" -> "(" + input + " > 0.0 ? " + input + " : " + input + " * 0.25)";
            case "SQUEEZE"          -> "clamp(" + input + " / 2.0, -1.0, 1.0)";
            default                 -> input;
        };
    }
    
    private String visitRangeChoice(DensityFunction node) throws Exception {
        String input = visit(getFunc(node, "input"));
        String minI = formatDouble(getDouble(node, "minInclusive"));
        String maxE = formatDouble(getDouble(node, "maxExclusive"));
        String whenIn = visit(getFunc(node, "whenInRange"));
        String whenOut = visit(getFunc(node, "whenOutOfRange"));
        return "((" + input + " >= " + minI + " && " + input + " < " + maxE + ") ? " + whenIn + " : " + whenOut + ")";
    }
    
    private String visitYClampedGradient(DensityFunction node) throws Exception {
        int fromY = getInt(node, "fromY");
        int toY = getInt(node, "toY");
        double fromVal = getDouble(node, "fromValue");
        double toVal = getDouble(node, "toValue");
        return "mix(" + formatDouble(fromVal) + ", " + formatDouble(toVal) + ", clamp((global_y - " + fromY + ".0) / " + (toY - fromY) + ".0, 0.0, 1.0))";
    }
    
    // ==================== NOISE fBm ====================
    
    private double getScaleSafety(DensityFunction node, String... fields) {
        for (String f : fields) {
            try { return getDouble(node, f); } catch (Exception ignored) {}
        }
        return 1.0;
    }

    private String visitNoise(DensityFunction node) throws Exception {
        NoiseInfo info = extractNoiseParams(node);
        double xzScale = getScaleSafety(node, "xzScale", "xz_scale");
        double yScale  = getScaleSafety(node, "yScale", "y_scale");
        return formatMojangNoiseCall(info, "vec3(global_x * " + formatDouble(xzScale) + ", global_y * " + formatDouble(yScale) + ", global_z * " + formatDouble(xzScale) + ")");
    }
    
    private String visitShiftedNoise(DensityFunction node) throws Exception {
        String sx = visit(getFunc(node, "shiftX"));
        String sy = visit(getFunc(node, "shiftY"));
        String sz = visit(getFunc(node, "shiftZ"));
        NoiseInfo info = extractNoiseParams(node);
        double xzScale = getScaleSafety(node, "xzScale", "xz_scale");
        double yScale  = getScaleSafety(node, "yScale", "y_scale");
        return formatMojangNoiseCall(info, "vec3(global_x * " + formatDouble(xzScale) + " + " + sx + ", global_y * " + formatDouble(yScale) + " + " + sy + ", global_z * " + formatDouble(xzScale) + " + " + sz + ")");
    }
    
    /**
     * ShiftA : Déformation spatiale X.
     */
    private String visitShiftA(DensityFunction node) throws Exception {
        NoiseInfo info = extractNoiseParams(node);
        if (info.amplitudes.length <= 1 && info.amplitudes[0] == 1.0 && info.firstOctave == -7 && info.offset1 == 0 && info.offset2 == 0) {
            // Fallback
            return "sample_improved_noise(vec3((global_x) * 0.25, 0.0, (global_z) * 0.25), 0) * 4.0";
        }
        return "(" + formatMojangNoiseCall(info, "vec3(global_x * 0.25, 0.0, global_z * 0.25)") + " * 4.0)";
    }
    
    /**
     * ShiftB : Déformation spatiale Z.
     */
    private String visitShiftB(DensityFunction node) throws Exception {
        NoiseInfo info = extractNoiseParams(node);
        if (info.amplitudes.length <= 1 && info.amplitudes[0] == 1.0 && info.firstOctave == -7 && info.offset1 == 0 && info.offset2 == 0) {
            return "sample_improved_noise(vec3((global_z) * 0.25, 0.0, (global_x) * 0.25), 0) * 4.0";
        }
        return "(" + formatMojangNoiseCall(info, "vec3(global_z * 0.25, global_x * 0.25, 0.0)") + " * 4.0)";
    }
    
    /**
     * Shift générique : déformation 3D.
     */
    private String visitShift(DensityFunction node) throws Exception {
        NoiseInfo info = extractNoiseParams(node);
        if (info.amplitudes.length <= 1 && info.amplitudes[0] == 1.0 && info.firstOctave == -7 && info.offset1 == 0 && info.offset2 == 0) {
            return "sample_improved_noise(vec3((global_x) * 0.25, global_y * 0.25, (global_z) * 0.25), 0) * 4.0"; // Fallback if no noise found
        }
        return "(" + formatMojangNoiseCall(info, "vec3(global_x * 0.25, global_y * 0.25, global_z * 0.25)") + " * 4.0)";
    }
    
    private String visitBlendedNoise(DensityFunction node) throws Exception {
        Object minLimit;
        Object maxLimit;
        Object mainNoise;
        try {
            minLimit = getObj(node, "minLimitNoise");
            maxLimit = getObj(node, "maxLimitNoise");
            mainNoise = getObj(node, "mainNoise");
        } catch (Exception e) {
            System.err.println("[GPU DEBUG] Cannot find BlendedNoise core fields! " + e.getMessage());
            return "0.0";
        }
        
        double xzMul = 1.0; double yMul = 1.0;
        double xzFac = 1.0; double yFac = 1.0;
        double smear = 8.0;
        
        try {
            xzMul = getDouble(node, "xzMultiplier");
            yMul = getDouble(node, "yMultiplier");
            xzFac = getDouble(node, "xzFactor");
            yFac = getDouble(node, "yFactor");
            smear = getDouble(node, "smearScaleMultiplier");
        } catch (Exception ignored) {}

        NoiseInfo minInfo = extractNoiseParamsFallback(minLimit);
        NoiseInfo maxInfo = extractNoiseParamsFallback(maxLimit);
        NoiseInfo mainInfo = extractNoiseParamsFallback(mainNoise);
        
        String g = "(" + formatMojangNoiseCall(mainInfo, "vec3(global_x * " + formatDouble(xzMul) + ", global_y * " + formatDouble(yMul) + ", global_z * " + formatDouble(xzMul) + ")") + " * " + formatDouble(smear) + ")";
        String h = formatMojangNoiseCall(minInfo, "vec3(global_x * " + formatDouble(xzFac) + ", global_y * " + formatDouble(yFac) + ", global_z * " + formatDouble(xzFac) + ")");
        String l = formatMojangNoiseCall(maxInfo, "vec3(global_x * " + formatDouble(xzFac) + ", global_y * " + formatDouble(yFac) + ", global_z * " + formatDouble(xzFac) + ")");

        return "mix(" + h + ", " + l + ", clamp(((" + g + ") / 10.0 + 1.0) / 2.0, 0.0, 1.0))";
    }
    
    private NoiseInfo extractNoiseParamsFallback(Object doublePerlin) {
        if (doublePerlin == null) return new NoiseInfo(-7, new double[]{1.0, 1.0}, 0, 0, 1.0);
        if (noiseRegistry.containsKey(doublePerlin)) return noiseRegistry.get(doublePerlin);
        // Use a wrapper dummy node to reuse the existing extraction logic
        return extractNoiseParams(new DensityFunction() {
            @Override public double compute(FunctionContext c) { return 0; }
            @Override public void fillArray(double[] array, ContextProvider p) {}
            @Override public DensityFunction mapAll(Visitor visitor) { return this; }
            @Override public double minValue() { return 0; }
            @Override public double maxValue() { return 0; }
            @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
            
            // Magic trick: override findNoiseHolder behavior in reflection by implementing a field called "value" pointing to doublePerlin
            public Object value = doublePerlin;
        });
    }
    
    // ==================== NOISE PARAMETER EXTRACTION ====================
    
    private record NoiseInfo(int firstOctave, double[] amplitudes, int offset1, int offset2, double doubleAmplitude) {}
    
    private NoiseInfo extractNoiseParams(DensityFunction node) {
        try {
            Object noiseHolder = findNoiseHolder(node);
            System.out.println("[GPU DEBUG] extractNoiseParams for node " + node.getClass().getSimpleName() + " found holder: " + (noiseHolder != null ? noiseHolder.getClass().getSimpleName() : "null"));
            if (noiseHolder == null) return new NoiseInfo(-7, new double[]{1.0, 1.0}, 0, 0, 1.0);
            
            Object doublePerlin = noiseHolder;
            if (noiseHolder instanceof net.minecraft.core.Holder<?> h) {
                doublePerlin = h.value();
            }
            
            // Robust unwrapping de NoiseHolder / NormalNoise / Holder vers NormalNoise
            while (doublePerlin != null && !getSimpleName(doublePerlin).equals("NormalNoise")) {
                Object next = null;
                // Essaie les méthodes classiques
                try { next = getObj(doublePerlin, "value"); } catch (Exception ignored) {}
                if (next == null) {
                    try { next = getObj(doublePerlin, "noise"); } catch (Exception ignored) {}
                }
                // Essaie via les champs directement si pas trouvé
                if (next == null) {
                    for (java.lang.reflect.Field f : doublePerlin.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object val = f.get(doublePerlin);
                        if (val != null) {
                            String name = getSimpleName(val);
                            if (name.equals("NormalNoise") || val instanceof net.minecraft.core.Holder<?>) {
                                next = val;
                                break;
                            }
                        }
                    }
                }
                if (next == null || next == doublePerlin) break; // dead end
                doublePerlin = next;
            }
            
            if (doublePerlin == null || !getSimpleName(doublePerlin).equals("NormalNoise")) {
                if (doublePerlin != null) {
                    System.out.println("[GPU DEBUG] Fallback to default noise info: doublePerlin is " + getSimpleName(doublePerlin));
                    for (java.lang.reflect.Field f : doublePerlin.getClass().getDeclaredFields()) {
                        System.out.println("[GPU DEBUG]  - Field: " + f.getType().getSimpleName() + " " + f.getName());
                    }
                } else {
                    System.out.println("[GPU DEBUG] Fallback to default noise info: doublePerlin is null");
                }
                return new NoiseInfo(-7, new double[]{1.0, 1.0}, 0, 0, 1.0);
            }
            
            System.out.println("[GPU DEBUG] Successfully extracted DoublePerlinNoiseSampler!");
            
            if (noiseRegistry.containsKey(doublePerlin)) {
                return noiseRegistry.get(doublePerlin);
            }
            
            double doubleAmplitude = 1.0;
            try {
                doubleAmplitude = getDouble(doublePerlin, "valueFactor");
            } catch (Exception e) {
                // Try first double as fallback
                for (java.lang.reflect.Field f : doublePerlin.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == double.class) {
                        try { doubleAmplitude = f.getDouble(doublePerlin); } catch (Exception ignored) {}
                        break;
                    }
                }
            }
            
            Object firstOctavePerlin = null;
            Object secondOctavePerlin = null;
            try {
                firstOctavePerlin = getObj(doublePerlin, "first");
                secondOctavePerlin = getObj(doublePerlin, "second");
            } catch (Exception e) {
                for (java.lang.reflect.Field f : doublePerlin.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        if (!f.getType().isPrimitive() && f.getType().getSimpleName().equals("PerlinNoise")) {
                            Object val = f.get(doublePerlin);
                            if (val != null) {
                                if (firstOctavePerlin == null) firstOctavePerlin = val;
                                else if (secondOctavePerlin == null) secondOctavePerlin = val;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
            
            int offset1 = allocateOctavesFor(firstOctavePerlin);
            int offset2 = allocateOctavesFor(secondOctavePerlin);
            
            int firstOctave = -7;
            double[] amplitudes = new double[]{1.0, 1.0};
            try {
                Object parameters = getObj(doublePerlin, "parameters");
                if (parameters != null) {
                    firstOctave = getInt(parameters, "firstOctave");
                    Object ampsList = getObj(parameters, "amplitudes");
                    amplitudes = extractDoubleArray(ampsList);
                }
            } catch (Exception e) {
                // Secondary check inside firstOctavePerlin wrapper if parameters fails
                try { firstOctave = getInt(firstOctavePerlin, "firstOctave"); } catch (Exception ignored) {}
                amplitudes = extractAmplitudesFromOctave(firstOctavePerlin);
            }
            
            NoiseInfo result = new NoiseInfo(firstOctave, amplitudes, offset1, offset2, doubleAmplitude);
            noiseRegistry.put(doublePerlin, result);
            return result;
            
        } catch (Exception e) {
            System.err.println("[GPU JIT] NoiseParams extraction fallback: " + e.getMessage());
            return new NoiseInfo(-7, new double[]{1.0, 1.0}, 0, 0, 1.0);
        }
    }
    
    private int allocateOctavesFor(Object octavePerlinNoiseSampler) throws Exception {
        if (octavePerlinNoiseSampler == null) return 0;
        
        Object[] levels = null;
        for (java.lang.reflect.Field f : octavePerlinNoiseSampler.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val = f.get(octavePerlinNoiseSampler);
            if (val != null && val.getClass().isArray() && val instanceof Object[]) {
                levels = (Object[]) val;
                break;
            }
        }
        if (levels == null) return 0;
        
        int startIndex = currentOctaveOffset;
        for (Object improvedNoiseSampler : levels) {
            byte[] serializedOctave = new byte[1040]; // 16 bytes for padding/coord, 1024 bytes for p
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(serializedOctave);
            buffer.order(java.nio.ByteOrder.nativeOrder());
            
            if (improvedNoiseSampler != null) {
                double[] coords = new double[3];
                int c = 0;
                int[] p = null;
                
                for (java.lang.reflect.Field f : improvedNoiseSampler.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == double.class) {
                        if (c < 3) coords[c++] = f.getDouble(improvedNoiseSampler);
                    } else if (f.getType() == byte[].class) {
                        byte[] byteArr = (byte[]) f.get(improvedNoiseSampler);
                        p = new int[byteArr.length];
                        for (int i=0; i<byteArr.length; i++) p[i] = byteArr[i] & 0xFF;
                    } else if (f.getType() == int[].class) {
                        p = (int[]) f.get(improvedNoiseSampler);
                    }
                }
                
                buffer.putFloat((float) coords[0]);
                buffer.putFloat((float) coords[1]);
                buffer.putFloat((float) coords[2]);
                buffer.putFloat(0f); // padding
                
                if (p != null && p.length >= 256) {
                    System.out.println("[GPU DEBUG] Successfully extracted permutation table of length " + p.length);
                    for (int i = 0; i < 256; i++) {
                        buffer.putInt(p[i] & 0xFF);
                    }
                } else {
                    System.out.println("[GPU DEBUG] WARNING: Permutation table is NULL or too short!");
                    buffer.position(buffer.position() + 1024);
                }
            } else {
                buffer.position(1040); // All zeros
            }
            
            globalPermutationsList.add(serializedOctave);
            currentOctaveOffset++;
        }
        return startIndex;
    }
    
    private double[] extractAmplitudesFromOctave(Object octavePerlinNoiseSampler) {
        try {
            for (java.lang.reflect.Field f : octavePerlinNoiseSampler.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(octavePerlinNoiseSampler);
                if (val != null && getSimpleName(val).equals("DoubleArrayList")) {
                    return extractDoubleArray(val);
                }
            }
            return new double[]{1.0, 1.0};
        } catch (Exception e) {
            return new double[]{1.0, 1.0};
        }
    }
    
    private Object findNoiseHolder(DensityFunction node) {
        String[] candidates = {"noise", "noiseData"};
        for (String name : candidates) {
            try {
                Object obj = getObj(node, name);
                if (obj != null) return obj;
            } catch (Exception ignored) {}
        }
        try {
            for (Field f : node.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(node);
                if (val instanceof net.minecraft.core.Holder<?>) return val;
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    private double[] extractDoubleArray(Object ampsObj) {
        if (ampsObj instanceof double[] arr) return arr;
        if (ampsObj instanceof List<?> list) {
            double[] result = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                result[i] = ((Number) list.get(i)).doubleValue();
            }
            return result;
        }
        try {
            int size = (int) ampsObj.getClass().getMethod("size").invoke(ampsObj);
            double[] result = new double[size];
            try {
                Method getDouble = ampsObj.getClass().getMethod("getDouble", int.class);
                for (int i = 0; i < size; i++) result[i] = (double) getDouble.invoke(ampsObj, i);
            } catch (NoSuchMethodException e) {
                Method get = ampsObj.getClass().getMethod("get", int.class);
                for (int i = 0; i < size; i++) result[i] = ((Number) get.invoke(ampsObj, i)).doubleValue();
            }
            return result;
        } catch (Exception e) {
            return new double[]{1.0, 1.0};
        }
    }
    
    private String formatMojangNoiseCall(NoiseInfo info, String posExpr) {
        int length = info.amplitudes.length;
        if (length == 0 || length > 16) {
            length = Math.min(Math.max(length, 1), 16);
        }

        StringBuilder ampsArray = new StringBuilder("float[" + length + "](");
        for (int i = 0; i < length; i++) {
            float amp = (i < info.amplitudes.length) ? (float) info.amplitudes[i] : 0.0f;
            ampsArray.append(formatFloat(amp));
            if (i < length - 1) ampsArray.append(", ");
        }
        ampsArray.append(")");

        return "mojang_double_perlin_noise_" + length + "(" + posExpr + ", " + formatDouble(info.doubleAmplitude) + ", " + info.firstOctave + ", " + info.offset1 + ", " + info.offset2 + ", " + ampsArray.toString() + ")";
    }

    public byte[] getPermutationData() {
        byte[] finalArray = new byte[globalPermutationsList.size() * 1040];
        int idx = 0;
        for (byte[] b : globalPermutationsList) {
            System.arraycopy(b, 0, finalArray, idx, 1040);
            idx += 1040;
        }
        return finalArray;
    }
    
    // ==================== SPLINE ====================
    
    private String visitSpline(DensityFunction node) throws Exception {
        CubicSpline<?, ?> rawSpline = (CubicSpline<?, ?>) getObj(node, "spline");
        Object coordinateObj = getObj(rawSpline, "coordinate");
        DensityFunction coordinateFunc = unwrapSplineCoordinate(coordinateObj);
        String coordinateExpr = visit(coordinateFunc);
        String methodName = generateSplineFunction(rawSpline);
        return methodName + "(" + coordinateExpr + ")";
    }
    
    private DensityFunction unwrapSplineCoordinate(Object coordinate) throws Exception {
        if (coordinate instanceof DensityFunction df) return df;
        
        try {
            Object holder = getObj(coordinate, "function");
            if (holder instanceof net.minecraft.core.Holder<?> h) {
                Object val = h.value();
                if (val instanceof DensityFunction df) return df;
            }
            if (holder instanceof DensityFunction df) return df;
        } catch (Exception ignored) {}
        
        try {
            Object holder = getObj(coordinate, "holder");
            if (holder instanceof net.minecraft.core.Holder<?> h) {
                Object val = h.value();
                if (val instanceof DensityFunction df) return df;
            }
        } catch (Exception ignored) {}
        
        Class<?> clazz = coordinate.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(coordinate);
                if (val instanceof DensityFunction df) return df;
                if (val instanceof net.minecraft.core.Holder<?> h) {
                    try {
                        Object inner = h.value();
                        if (inner instanceof DensityFunction df) return df;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        
        for (Class<?> iface : coordinate.getClass().getInterfaces()) {
            if (DensityFunction.class.isAssignableFrom(iface)) {
                return (DensityFunction) coordinate;
            }
        }
        
        System.err.println("[GPU JIT] Spline$Coordinate unwrap échoué: " + coordinate.getClass().getName());
        for (Field f : coordinate.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            System.err.println("[GPU JIT]   " + f.getName() + " : " + f.getType().getSimpleName() + " = " + f.get(coordinate));
        }
        throw new RuntimeException("Unwrap Spline$Coordinate échoué");
    }

    private String generateSplineFunction(CubicSpline<?, ?> rawSpline) throws Exception {
        String methodName = "eval_spline_" + (splineIndex++);
        globalScopeBuilder.append("float ").append(methodName).append("(float inputParam) {\n");
        
        String splineType = getSimpleName(rawSpline);
        if ("Constant".equals(splineType)) {
            float val = getFloat(rawSpline, "value");
            globalScopeBuilder.append("    return ").append(formatFloat(val)).append(";\n}\n\n");
            return methodName;
        }

        if ("Multipoint".equals(splineType)) {
            float[] locs = (float[]) getObj(rawSpline, "locations");
            float[] derivs = (float[]) getObj(rawSpline, "derivatives");
            @SuppressWarnings("unchecked")
            List<CubicSpline<?, ?>> containedValues = (List<CubicSpline<?, ?>>) getObj(rawSpline, "values");
            
            float firstVal = extractStaticValue(containedValues.get(0));
            float lastVal = extractStaticValue(containedValues.get(containedValues.size() - 1));
            
            globalScopeBuilder.append("    if (inputParam <= ").append(formatFloat(locs[0])).append(") { return ").append(formatFloat(firstVal)).append("; }\n");
            globalScopeBuilder.append("    if (inputParam >= ").append(formatFloat(locs[locs.length - 1])).append(") { return ").append(formatFloat(lastVal)).append("; }\n");

            for (int i = 0; i < locs.length - 1; i++) {
                float loc0 = locs[i], loc1 = locs[i + 1];
                float val0 = extractStaticValue(containedValues.get(i));
                float val1 = extractStaticValue(containedValues.get(i + 1));
                float der0 = derivs[i], der1 = derivs[i + 1];
                
                String cond = (i == locs.length - 2) ? "else" : "else if (inputParam < " + formatFloat(loc1) + ")";
                
                globalScopeBuilder.append("    ").append(cond).append(" {\n");
                globalScopeBuilder.append("        float t = (inputParam - ").append(formatFloat(loc0)).append(") / ").append(formatFloat(loc1 - loc0)).append(";\n");
                globalScopeBuilder.append("        float t2 = t * t; float t3 = t2 * t;\n");
                globalScopeBuilder.append("        return (2.0*t3 - 3.0*t2 + 1.0)*").append(formatFloat(val0))
                                  .append(" + (t3 - 2.0*t2 + t)*").append(formatFloat(der0))
                                  .append(" + (-2.0*t3 + 3.0*t2)*").append(formatFloat(val1))
                                  .append(" + (t3 - t2)*").append(formatFloat(der1)).append(";\n");
                globalScopeBuilder.append("    }\n");
            }
            globalScopeBuilder.append("    return 0.0;\n}\n\n");
            return methodName;
        }
        
        throw new RuntimeException("CubicSpline inconnu: " + splineType);
    }
    
    private float extractStaticValue(CubicSpline<?, ?> spline) throws Exception {
        if ("Constant".equals(getSimpleName(spline))) return getFloat(spline, "value");
        return 0.0f; 
    }
    
    // ==================== UNWRAP HELPERS ====================
    
    private DensityFunction unwrapHolderHolder(DensityFunction node) throws Exception {
        try {
            Object holder = getObj(node, "function");
            Method valueMethod = holder.getClass().getMethod("value");
            valueMethod.setAccessible(true);
            return (DensityFunction) valueMethod.invoke(holder);
        } catch (Exception e1) {
            try { return unwrapByField(node, "function"); } catch (Exception e2) {
                return unwrapFirstDensityFunctionField(node);
            }
        }
    }
    
    private DensityFunction unwrapMarker(DensityFunction node) throws Exception {
        String[] names = {"wrapped", "input", "argument"};
        for (String name : names) {
            try { return getFunc(node, name); } catch (Exception ignored) {}
        }
        return unwrapFirstDensityFunctionField(node);
    }
    
    private DensityFunction unwrapSingleArg(DensityFunction node) throws Exception {
        String[] names = {"input", "wrapped", "argument", "argument1"};
        for (String name : names) {
            try { return getFunc(node, name); } catch (Exception ignored) {}
        }
        return unwrapFirstDensityFunctionField(node);
    }
    
    private DensityFunction unwrapFirstDensityFunctionField(Object obj) throws Exception {
        for (Field f : obj.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object val = f.get(obj);
            if (val instanceof DensityFunction df) return df;
        }
        throw new RuntimeException("Aucun champ DensityFunction dans " + obj.getClass().getName());
    }
    
    private DensityFunction unwrapByField(DensityFunction node, String fieldName) throws Exception {
        Field f = node.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        Object holder = f.get(node);
        if (holder instanceof net.minecraft.core.Holder<?> h) return (DensityFunction) h.value();
        return (DensityFunction) holder;
    }

    // ==================== REFLECTION UTILS ====================
    
    private String getSimpleName(Object obj) {
        String name = obj.getClass().getSimpleName();
        if (name.contains("$")) return name.substring(name.lastIndexOf('$') + 1);
        return name;
    }
    
    private DensityFunction getFunc(Object obj, String name) throws Exception {
        return (DensityFunction) getObj(obj, name);
    }
    
    private double getDouble(Object obj, String name) throws Exception {
        return ((Number) getObj(obj, name)).doubleValue();
    }
    
    private int getInt(Object obj, String name) throws Exception {
        return ((Number) getObj(obj, name)).intValue();
    }
    
    private float getFloat(Object obj, String name) throws Exception {
        return ((Number) getObj(obj, name)).floatValue();
    }
    
    private Object getObj(Object obj, String name) throws Exception {
        try {
            Method m = obj.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (NoSuchMethodException ignored) {}
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (NoSuchFieldException ignored) {}
        throw new NoSuchMethodException("Ni méthode ni champ '" + name + "' sur " + obj.getClass().getSimpleName());
    }
    
    // ==================== FORMAT UTILS ====================
    
    private String formatDouble(double val) {
        if (val == (long) val) return (long) val + ".0";
        return String.valueOf(val);
    }
    
    private String formatFloat(float val) {
        if (val == (int) val) return (int) val + ".0";
        return String.valueOf(val);
    }
}
