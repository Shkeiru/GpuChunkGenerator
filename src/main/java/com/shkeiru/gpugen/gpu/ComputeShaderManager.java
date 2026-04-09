package com.shkeiru.gpugen.gpu;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Moteur JIT : Compile le code GLSL généré dynamiquement à la volée.
 */
public class ComputeShaderManager {

    private static final ComputeShaderManager INSTANCE = new ComputeShaderManager();

    public static ComputeShaderManager getInstance() {
        return INSTANCE;
    }

    private long windowHandle = MemoryUtil.NULL;
    private int computeProgram = 0;
    private int outputSSBO = 0;
    private int permutationSSBO = 0;

    private static final int OUTPUT_BUFFER_SIZE = 16 * 16 * 4096 * 4;

    private ComputeShaderManager() { }

    public boolean initHeadlessContext() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit()) {
            System.err.println("[GPU WorldGen] Impossible d'initialiser GLFW.");
            return false;
        }

        // Fenêtre Headless
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        windowHandle = GLFW.glfwCreateWindow(1, 1, "Compute Context", MemoryUtil.NULL, MemoryUtil.NULL);
        if (windowHandle == MemoryUtil.NULL) {
            return false;
        }

        GLFW.glfwMakeContextCurrent(windowHandle);
        GL.createCapabilities();
        
        return true;
    }

    /**
     * Paramétrique : Compile le Compute Shader en injectant le code dynamique JIT généré.
     * @param glslSource Le code source final produit par le DensityToGLSLTranspiler.
     * @param permutationData Les données de permutation formatées pour l'octave struct GLSL.
     */
    public void setupComputePipeline(String glslSource, byte[] permutationData) {
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shader, glslSource);
        GL20C.glCompileShader(shader);

        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String errorLog = GL20C.glGetShaderInfoLog(shader);
            throw new RuntimeException("Erreur de Syntaxe JIT (Datapack incompatible) :\n" + errorLog);
        }

        computeProgram = GL20C.glCreateProgram();
        GL20C.glAttachShader(computeProgram, shader);
        GL20C.glLinkProgram(computeProgram);
        GL20C.glDeleteShader(shader); 

        if (GL20C.glGetProgrami(computeProgram, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
            throw new RuntimeException("Erreur linkage JIT: " + GL20C.glGetProgramInfoLog(computeProgram));
        }
        
        System.out.println("[GPU JIT] Compilation GLSL Dynamique Réussie !");

        outputSSBO = GL43C.glGenBuffers();
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, outputSSBO);
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, OUTPUT_BUFFER_SIZE, GL43C.GL_DYNAMIC_COPY);
        
        permutationSSBO = GL43C.glGenBuffers();
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, permutationSSBO);
        if (permutationData != null && permutationData.length > 0) {
            ByteBuffer buffer = MemoryUtil.memAlloc(permutationData.length);
            buffer.put(permutationData).flip();
            GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, buffer, GL43C.GL_STATIC_DRAW);
            MemoryUtil.memFree(buffer);
        } else {
            GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, 4L, GL43C.GL_STATIC_DRAW);
        }
        
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
    }

    public void dispatchChunkGeneration(int chunkX, int chunkZ) {
        GL20C.glUseProgram(computeProgram);
        
        int locX = GL20C.glGetUniformLocation(computeProgram, "chunkX");
        int locZ = GL20C.glGetUniformLocation(computeProgram, "chunkZ");
        GL20C.glUniform1i(locX, chunkX);
        GL20C.glUniform1i(locZ, chunkZ);

        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, outputSSBO);
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, permutationSSBO);

        // Dimension 2D (L'axe Y est géré à l'intérieur du Kernel par la boucle for)
        GL43C.glDispatchCompute(1, 1, 1);

        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    public byte[] fetchAndFreeResult() {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, outputSSBO);
        ByteBuffer rawMappedBuffer = GL43C.glMapBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, GL43C.GL_READ_ONLY);

        if (rawMappedBuffer == null) {
            throw new RuntimeException("Impossible de mapper l'Output SSBO.");
        }

        try {
            byte[] safeJavaData = new byte[OUTPUT_BUFFER_SIZE];
            rawMappedBuffer.get(safeJavaData);
            return safeJavaData;
        } finally {
            GL43C.glUnmapBuffer(GL43C.GL_SHADER_STORAGE_BUFFER);
        }
    }

    public void cleanup() {
        if (computeProgram != 0) GL20C.glDeleteProgram(computeProgram);
        if (outputSSBO != 0) GL43C.glDeleteBuffers(outputSSBO);
        if (permutationSSBO != 0) GL43C.glDeleteBuffers(permutationSSBO);
        if (windowHandle != MemoryUtil.NULL) GLFW.glfwDestroyWindow(windowHandle);
    }
}
