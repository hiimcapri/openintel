package dev.openintel;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;

import java.nio.charset.StandardCharsets;

public final class CleanFontShaderTest {
    public static void main(String[] args) throws Exception {
        var errors = GLFWErrorCallback.createPrint(System.err);
        GLFW.glfwSetErrorCallback(errors);
        long window = 0;
        int vertex = 0, fragment = 0, program = 0;
        try {
            check(GLFW.glfwInit(), "GLFW initialization");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(32, 32, "OpenIntel font shader test", 0, 0);
            check(window != 0, "Hidden OpenGL context");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            vertex = compile(GL33C.GL_VERTEX_SHADER, "/assets/minecraft/shaders/core/position_tex_color.vsh");
            for (boolean intensity : new boolean[]{false, true}) {
                fragment = compile(GL33C.GL_FRAGMENT_SHADER, "/assets/openintel/shaders/core/clean_font.fsh", intensity);
                program = GL33C.glCreateProgram();
                GL33C.glAttachShader(program, vertex);
                GL33C.glAttachShader(program, fragment);
                GL33C.glLinkProgram(program);
                check(GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == GL33C.GL_TRUE,
                        "Font shader link: " + GL33C.glGetProgramInfoLog(program));
                check(GL33C.glGetUniformLocation(program, "Sampler0") >= 0, "Font atlas sampler is linked");
                check(GL33C.glGetUniformBlockIndex(program, "DynamicTransforms") != GL33C.GL_INVALID_INDEX,
                        "GUI transform block is linked");
                check(GL33C.glGetUniformBlockIndex(program, "Projection") != GL33C.GL_INVALID_INDEX,
                        "GUI projection block is linked");
                verifyCoverage(program, intensity);
                check(GL33C.glGetError() == GL33C.GL_NO_ERROR, "No OpenGL errors");
                GL33C.glDeleteProgram(program);
                GL33C.glDeleteShader(fragment);
                program = fragment = 0;
            }
            System.out.println("Both clean font shader variants compiled, linked, and coverage-tested on " + GL33C.glGetString(GL33C.GL_RENDERER));
        } finally {
            if (program != 0) GL33C.glDeleteProgram(program);
            if (fragment != 0) GL33C.glDeleteShader(fragment);
            if (vertex != 0) GL33C.glDeleteShader(vertex);
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private static int compile(int type, String resource) throws Exception {
        return compile(type, resource, false);
    }

    private static int compile(int type, String resource, boolean intensity) throws Exception {
        String source;
        try (var input = CleanFontShaderTest.class.getResourceAsStream(resource)) {
            check(input != null, "Shader resource present: " + resource);
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (intensity) source = source.replace("#version 330", "#version 330\n#define FONT_INTENSITY");
        int shader = GL33C.glCreateShader(type);
        GL33C.glShaderSource(shader, source);
        GL33C.glCompileShader(shader);
        if (GL33C.glGetShaderi(shader, GL33C.GL_COMPILE_STATUS) != GL33C.GL_TRUE) {
            String log = GL33C.glGetShaderInfoLog(shader);
            GL33C.glDeleteShader(shader);
            throw new AssertionError(resource + ": " + log);
        }
        return shader;
    }

    private static void verifyCoverage(int program, boolean intensity) {
        int vao = GL33C.glGenVertexArrays(), vertices = GL33C.glGenBuffers();
        int transforms = GL33C.glGenBuffers(), projection = GL33C.glGenBuffers();
        int texture = GL33C.glGenTextures();
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            GL33C.glUseProgram(program);
            GL33C.glBindVertexArray(vao);
            GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER, vertices);
            GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL33C.GL_STATIC_DRAW);
            int position = GL33C.glGetAttribLocation(program, "Position");
            GL33C.glEnableVertexAttribArray(position);
            GL33C.glVertexAttribPointer(position, 3, GL33C.GL_FLOAT, false, 12, 0L);
            GL33C.glVertexAttrib2f(GL33C.glGetAttribLocation(program, "UV0"), 0.5f, 0.5f);
            GL33C.glVertexAttrib4f(GL33C.glGetAttribLocation(program, "Color"), 1f, 0.5f, 0.25f, 1f);
            float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            float[] dynamic = new float[40];
            System.arraycopy(identity, 0, dynamic, 0, 16);
            java.util.Arrays.fill(dynamic, 16, 20, 1f);
            System.arraycopy(identity, 0, dynamic, 24, 16);
            GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, transforms);
            GL33C.glBufferData(GL33C.GL_UNIFORM_BUFFER, dynamic, GL33C.GL_STATIC_DRAW);
            GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, 0, transforms);
            GL33C.glUniformBlockBinding(program, GL33C.glGetUniformBlockIndex(program, "DynamicTransforms"), 0);
            GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, projection);
            GL33C.glBufferData(GL33C.GL_UNIFORM_BUFFER, identity, GL33C.GL_STATIC_DRAW);
            GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, 1, projection);
            GL33C.glUniformBlockBinding(program, GL33C.glGetUniformBlockIndex(program, "Projection"), 1);
            GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
            GL33C.glUniform1i(GL33C.glGetUniformLocation(program, "Sampler0"), 0);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_LINEAR);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, GL33C.GL_LINEAR);
            GL33C.glPixelStorei(GL33C.GL_UNPACK_ALIGNMENT, 1);
            GL33C.glDisable(GL33C.GL_BLEND);
            GL33C.glDisable(GL33C.GL_DEPTH_TEST);
            GL33C.glViewport(0, 0, 32, 32);
            for (int coverage : new int[]{0, 128, 255}) {
                var texel = stack.malloc(4);
                if (intensity) texel.put((byte) coverage);
                else texel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) coverage);
                texel.flip();
                GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, intensity ? GL33C.GL_R8 : GL33C.GL_RGBA8,
                        1, 1, 0, intensity ? GL33C.GL_RED : GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, texel);
                GL33C.glClearColor(0f, 0f, 0f, 0f);
                GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
                GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
                var pixel = stack.malloc(4);
                GL33C.glReadPixels(16, 16, 1, 1, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pixel);
                check(Math.abs((pixel.get(3) & 255) - coverage) <= 1,
                        "Correct " + (intensity ? "red" : "alpha") + " coverage channel: " + coverage);
                check((pixel.get(0) & 255) == (coverage == 0 ? 0 : 255), "Transparent padding and text color preserved");
            }
        } finally {
            GL33C.glDeleteTextures(texture);
            GL33C.glDeleteBuffers(projection);
            GL33C.glDeleteBuffers(transforms);
            GL33C.glDeleteBuffers(vertices);
            GL33C.glDeleteVertexArrays(vao);
        }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
