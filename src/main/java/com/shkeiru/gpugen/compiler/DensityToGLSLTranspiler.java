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

    private enum EvalMode { PHASE1A_2D, PHASE1B_GRID, PHASE2_VOXEL }
    private EvalMode currentMode = EvalMode.PHASE2_VOXEL;
    private int interpolatedGridCounter = 0;
    
    private final int minY;
    private final int maxY;
    private final int cellCountY;
    private final int gridSize;

    private int varCounter = 0;
    private boolean inCache2D = false;
    
    private final StringBuilder globalScopeBuilder = new StringBuilder();
    private final StringBuilder setup2DSnippets = new StringBuilder();
    private final StringBuilder gridSnippets = new StringBuilder();
    private final StringBuilder pixelSnippets = new StringBuilder();
    
    private final Map<Object, NoiseInfo> noiseRegistry = new IdentityHashMap<>();
    private final java.util.List<byte[]> globalPermutationsList = new java.util.ArrayList<>();
    private int currentOctaveOffset = 0;
    
    private final Map<DensityFunction, String> evaluatedNodes = new IdentityHashMap<>();
    private final Map<DensityFunction, Integer> evaluated2DIndices = new IdentityHashMap<>();

    public DensityToGLSLTranspiler(int minY, int height) {
        this.minY = minY;
        this.maxY = minY + height;
        this.cellCountY = (height / 8) + 1;
        this.gridSize = 25 * this.cellCountY;
    }

    private void appendSnippet(String snippet) {
        if (currentMode == EvalMode.PHASE1A_2D) {
            setup2DSnippets.append(snippet);
        } else if (currentMode == EvalMode.PHASE1B_GRID) {
            gridSnippets.append(snippet);
        } else {
            pixelSnippets.append(snippet);
        }
    }

    public String compileShaderSource(DensityFunction root) {
        String inlinedExpression = visit(root);
        
        StringBuilder fullShader = new StringBuilder();
        fullShader.append(GLSLSnippets.getComputeHeaderAndNoiseBase());
        
        fullShader.append("\n// --- SCOPE GLOBAL (SPLINES JIT) ---\n");
        fullShader.append(globalScopeBuilder.toString());
        
        fullShader.append("\n// --- SHARED DATA ---\n");
        for (int i = 0; i < interpolatedGridCounter; i++) {
            fullShader.append("    shared float grid_").append(i).append("[").append(gridSize).append("];\n");
        }
        if (varCounter > 0) fullShader.append("    shared float cache2D_array[25][").append(varCounter).append("];\n");
        
        fullShader.append("""
        void main() {
            uint idx = gl_LocalInvocationIndex;
            
            // --- PHASE 1.A : CACHE 2D ---
            while (idx < 25u) {
                uint gridX = idx % 5u;
                uint gridZ = idx / 5u;
                float global_x = float(chunkX * 16 + int(gridX) * 4);
                float global_z = float(chunkZ * 16 + int(gridZ) * 4);
                float global_y = 0.0;
        """);
        
        fullShader.append(setup2DSnippets.toString());
        
        fullShader.append("""
                idx += 256u;
            }
            barrier();
        """);
        
        if (interpolatedGridCounter > 0) {
            fullShader.append("""
            // --- PHASE 1.B : AST 3D SUR LA GRILLE (HYBRIDE) ---
            idx = gl_LocalInvocationIndex;
            while (idx < """).append(gridSize).append("""
            u) {
                uint gridX = idx % 5u;
                uint gridY = (idx / 5u) % """).append(cellCountY).append("""
                u;
                uint gridZ = idx / """).append(5 * cellCountY).append("""
                u;
                uint idx2D = gridZ * 5u + gridX;
                
                float global_x = float(chunkX * 16 + int(gridX) * 4);
                float global_y = float(""").append(minY).append(" + int(gridY) * 8);\n");
            fullShader.append("""
                float global_z = float(chunkZ * 16 + int(gridZ) * 4);
        """);
            fullShader.append(gridSnippets.toString());
            fullShader.append("""
                idx += 256u;
            }
            barrier();
            """);
        }
            
        fullShader.append("""
            // --- PHASE 2 : INTERPOLATION ET RLE (VOXEL PAR VOXEL) ---
            uint b_idx = gl_LocalInvocationIndex;
            if (b_idx < 256u) {
                int localX = int(b_idx % 16u);
                int localZ = int(b_idx / 16u);
                int cx = localX / 4;
                int cz = localZ / 4;
                float tx = float(localX % 4) / 4.0;
                float tz = float(localZ % 4) / 4.0;
                
                uint write_offset = uint((localZ * 16 + localX) * """).append(maxY - minY).append("""
                );
                uint current_id = 0u;
                uint run_count = 0u;
                
                for (int y_iter = """).append(minY).append("; y_iter < ").append(maxY).append("""
                ; y_iter++) {
                    int cy = (y_iter - """).append(minY).append("""
                    ) / 8;
                    float ty = float((y_iter - """).append(minY).append("""
                    ) % 8) / 8.0;
                    
                    float global_x = float(chunkX * 16 + localX);
                    float global_y = float(y_iter);
                    float global_z = float(chunkZ * 16 + localZ);
                    
                    uint idx2D = uint(cz) * 5u + uint(cx);
        """);
        
        fullShader.append(pixelSnippets.toString());
        fullShader.append("            float final_density = ").append(inlinedExpression).append(";\n");

        fullShader.append("""
                    uint block_id = 0u; // Air
                    if (final_density > 0.0) block_id = 1u; // Roche
                    else if (y_iter < 63) block_id = 2u; // Eau
                    
                    if (y_iter == """).append(minY).append("""
                    ) {
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
        }
        """);
        
        return fullShader.toString();
    }

    // ==================== VISITEUR PRINCIPAL ====================

    private String visit(DensityFunction node) {
        String typeName = getSimpleName(node);
        if (evaluatedNodes.containsKey(node)) {
            if (evaluated2DIndices.containsKey(node) && !inCache2D) {
                return "cache2D_array[idx2D][" + evaluated2DIndices.get(node) + "u]";
            }
            return evaluatedNodes.get(node);
        }
        String result;
        
        try {
            result = switch (typeName) {
                // --- STRUCTURELS (Pass-Through) ---
                case "HolderHolder"       -> visit(unwrapHolderHolder(node));
                case "Marker"             -> visit(unwrapMarker(node));
                case "Interpolated"       -> {
                    EvalMode oldMode = currentMode;
                    currentMode = EvalMode.PHASE1B_GRID;
                    int myGridId = interpolatedGridCounter++;
                    
                    String gridExpr = visit(unwrapMarker(node));
                    gridSnippets.append("                grid_").append(myGridId).append("[idx] = ").append(gridExpr).append(";\n");
                    
                    currentMode = oldMode;
                    
                    String n = "grid_" + myGridId;
                    yield "mix(" +
                           "  mix(mix(" + n + "[cz * " + (5 * cellCountY) + "u + cy * 5u + cx], " + n + "[cz * " + (5 * cellCountY) + "u + cy * 5u + (cx + 1u)], tx), " +
                           "      mix(" + n + "[cz * " + (5 * cellCountY) + "u + (cy + 1u) * 5u + cx], " + n + "[cz * " + (5 * cellCountY) + "u + (cy + 1u) * 5u + (cx + 1u)], tx), ty), " +
                           "  mix(mix(" + n + "[(cz + 1u) * " + (5 * cellCountY) + "u + cy * 5u + cx], " + n + "[(cz + 1u) * " + (5 * cellCountY) + "u + cy * 5u + (cx + 1u)], tx), " +
                           "      mix(" + n + "[(cz + 1u) * " + (5 * cellCountY) + "u + (cy + 1u) * 5u + cx], " + n + "[(cz + 1u) * " + (5 * cellCountY) + "u + (cy + 1u) * 5u + (cx + 1u)], tx), ty), " +
                           "tz)";
                }
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

        int myVarId = ++varCounter;
        String varName = (inCache2D ? "var2D_" : "var3D_") + myVarId;
        
        if (inCache2D) {
            setup2DSnippets.append("                float ").append(varName).append(" = ").append(result).append(";\n");
            setup2DSnippets.append("                cache2D_array[idx][").append(myVarId - 1).append("u] = ").append(varName).append(";\n");
            evaluated2DIndices.put(node, myVarId - 1);
        } else {
            appendSnippet("                float " + varName + " = " + result + ";\n");
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
                int[] p = null;
                
                try {
                    coords[0] = getDouble(improvedNoiseSampler, "xo");
                    coords[1] = getDouble(improvedNoiseSampler, "yo");
                    coords[2] = getDouble(improvedNoiseSampler, "zo");
                } catch (Exception e) {
                    java.lang.reflect.Field[] fields = improvedNoiseSampler.getClass().getDeclaredFields();
                    java.util.Arrays.sort(fields, java.util.Comparator.comparing(java.lang.reflect.Field::getName));
                    int cc = 0;
                    for (java.lang.reflect.Field f : fields) {
                        f.setAccessible(true);
                        if (f.getType() == double.class && cc < 3) coords[cc++] = f.getDouble(improvedNoiseSampler);
                    }
                }
                
                for (java.lang.reflect.Field f : improvedNoiseSampler.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == byte[].class) {
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
        return inlineSpline(rawSpline);
    }

    private String inlineSpline(CubicSpline<?, ?> rawSpline) throws Exception {
        String splineType = getSimpleName(rawSpline);
        if ("Constant".equals(splineType)) {
            return formatFloat(getFloat(rawSpline, "value"));
        }
        if ("Multipoint".equals(splineType)) {
            Object coordinateObj = getObj(rawSpline, "coordinate");
            DensityFunction coordinateFunc = null;
            if (coordinateObj instanceof DensityFunction func) {
                coordinateFunc = func;
            } else {
                for (java.lang.reflect.Field f : coordinateObj.getClass().getDeclaredFields()) {
                    if (DensityFunction.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        coordinateFunc = (DensityFunction) f.get(coordinateObj);
                        break;
                    }
                }
            } 
            String p = visit(coordinateFunc);

            float[] locs = (float[]) getObj(rawSpline, "locations");
            float[] derivs = (float[]) getObj(rawSpline, "derivatives");
            @SuppressWarnings("unchecked")
            List<CubicSpline<?, ?>> containedValues = (List<CubicSpline<?, ?>>) getObj(rawSpline, "values");

            List<String> vals = new java.util.ArrayList<>();
            for (CubicSpline<?, ?> child : containedValues) {
                vals.add(inlineSpline(child)); // Récursion vitale pour les splines imbriquées !
            }

            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < locs.length - 1; i++) {
                float loc0 = locs[i], loc1 = locs[i + 1];
                String val0 = vals.get(i), val1 = vals.get(i + 1);
                float der0 = derivs[i], der1 = derivs[i + 1];
                float delta = loc1 - loc0;

                String t = "((" + p + " - " + formatFloat(loc0) + ") / " + formatFloat(delta) + ")";
                String t2 = "(" + t + " * " + t + ")";
                String t3 = "(" + t2 + " * " + t + ")";

                String hermite = "((2.0*" + t3 + " - 3.0*" + t2 + " + 1.0)*(" + val0 + ") + " +
                                 "(" + t3 + " - 2.0*" + t2 + " + " + t + ")*" + formatFloat(der0 * delta) + " + " +
                                 "(-2.0*" + t3 + " + 3.0*" + t2 + ")*(" + val1 + ") + " +
                                 "(" + t3 + " - " + t2 + ")*" + formatFloat(der1 * delta) + ")";

                if (i == 0) {
                    sb.append("(").append(p).append(" <= ").append(formatFloat(loc0)).append(") ? (").append(val0).append(") : ");
                }
                if (i == locs.length - 2) {
                    sb.append("(").append(p).append(" < ").append(formatFloat(loc1)).append(") ? ").append(hermite).append(" : (").append(val1).append(")");
                } else {
                    sb.append("(").append(p).append(" < ").append(formatFloat(loc1)).append(") ? ").append(hermite).append(" : ");
                }
            }
            sb.append(")");
            return sb.toString();
        }
        return "0.0";
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
