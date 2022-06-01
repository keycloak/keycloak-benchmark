package de.alech.jit;

import de.alech.jit.agent.Hook;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.CodeSource;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BuilderMain {
    // data for the manifest of the agent.jar
    private static String manifestData =
            "Manifest-Version: 1.0\n" +
            "Agent-Class: de.alech.jit.agent.JavaAgent\n" +
            "Premain-Class: de.alech.jit.agent.JavaAgent\n" +
            "Can-Redefine-Classes: true\n" +
            "Can-Retransform-Classes: true\n" +
            "Main-Class: de.alech.jit.agent.DynamicInstrumentationMain\n";

    // Build an agent jar file based on the hooks definition file given on the command line.
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar jit.jar /path/to/hooks.txt [agent-output.jar]");
            System.exit(1);
        }
        String hooksFilename = args[0];
        String outputFilename = (args.length > 1) ? args[1] : "agent.jar";
        File hooksFile = new File(hooksFilename);
        if (! hooksFile.exists()) {
            System.err.println("Hooks file " + hooksFilename + " not found!");
            System.exit(2);
        }
        try {
            Manifest mf = new Manifest(new ByteArrayInputStream(manifestData.getBytes(Charset.forName("US-ASCII"))));
            JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputFilename), mf);

            // add all our classes to agent JAR file
            CodeSource src = BuilderMain.class.getProtectionDomain().getCodeSource();
            if (src != null) {
                URL jar = src.getLocation();
                ZipInputStream inputJar = new ZipInputStream(jar.openStream());
                while(true) {
                    ZipEntry e = inputJar.getNextEntry();
                    if (e == null)
                        break;
                    String name = e.getName();
                    if (! name.equals("META-INF/MANIFEST.MF")) {
                        jos.putNextEntry(new JarEntry(name));
                        writeInputStreamToOutputStream(inputJar, jos);
                    }
                }
            }

            // add hooks.txt to JAR
            jos.putNextEntry(new JarEntry("hooks.txt"));
            writeInputStreamToOutputStream(new FileInputStream(hooksFile), jos);

            // add hook code files to JAR
            jos.putNextEntry(new JarEntry("hooks/"));
            BufferedReader hookReader = new BufferedReader(new InputStreamReader(new FileInputStream(hooksFile)));
            while (hookReader.ready()) {
                String line = hookReader.readLine();
                String hooksBaseDir = hooksFile.getParent();
                if (hooksBaseDir == null) {
                    hooksBaseDir = "./";
                }
                Hook hook = new Hook(line, hooksBaseDir);
                jos.putNextEntry(new JarEntry("hooks/" + hook.codeFileName));
                writeInputStreamToOutputStream(new ByteArrayInputStream(hook.codePatch.getBytes("UTF-8")), jos);
            }
            jos.close();
        } catch (Exception e) {
            System.err.println("Failed to build agent JAR: " + e);
            e.printStackTrace(System.err);
            System.exit(3);
        }
    }

    private static String classNameToFilename(String className, boolean leadingSlash) {
        String fileName = leadingSlash ? "/" : "";
        fileName += className.replaceAll("\\.", "/");
        fileName += ".class";
        return fileName;
    }

    private static void writeInputStreamToOutputStream(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[1024];
        int len = is.read(buffer);
        while (len != -1) {
            os.write(buffer, 0, len);
            len = is.read(buffer);
        }
        os.flush();
    }
}
