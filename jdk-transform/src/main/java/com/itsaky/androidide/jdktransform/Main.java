package com.itsaky.androidide.jdktransform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.Objects;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Generates a JDK image using the <code>jlink</code> tool with
 * a single <code>java.base</code> module which contains classes
 * from the Android SDK.
 * 
 * CompilerModuleGenerator
 */
public class Main {

    private final Arguments arg;
    private final Exec exec;
    private final String jlinkVersion;

    public Main(Arguments arg, Exec exec, String jlinkVersion) {
        this.arg = arg;
        this.exec = exec;
        this.jlinkVersion = jlinkVersion;
    }

    private File generate() throws FileNotFoundException, IOException, InterruptedException {
        System.out.println("Generating java.base module descriptor...");

        final var sb = new StringBuilder();
        final var packages = new TreeSet<String>();
        sb.append("module java.base {");

        try (final var zip = new ZipFile(arg.androidJar);) {
            final var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                final var entry = iterator.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                var name = entry.getName();
                if (!name.contains("/")) {
                    continue;
                }

                name = name.substring(0, name.lastIndexOf('/'));
                name = name.replace('/', '.');
                packages.add(name);
            }
        }

        System.out.println("Found " + packages.size() + " packages in android.jar");
        for (var name : packages) {
            sb.append("\n    exports ");
            sb.append(name);
            sb.append(';');
        }

        sb.append("\n}");

        final var out = new File("./temp/module-info.java");
        out.getParentFile().delete();
        out.getParentFile().mkdirs();

        try (var outStream = new FileOutputStream(out)) {
            outStream.write(sb.toString().getBytes());
            outStream.flush();
        }

        if (!out.exists()) {
            throw new RuntimeException("Unable to generate module-info.java");
        }

        System.out.println("module-info.java has been written to " + out.getPath());
        return compileModuleDescriptor(out);
    }

    private File compileModuleDescriptor(File moduleInfoJava) throws IOException, InterruptedException {
        System.out.println("Compiling module descriptor...");
        final var builder = new ProcessBuilder(
                exec.javac.getAbsolutePath(),
                "--system=none",
                "--patch-module=java.base=" + arg.androidJar.getAbsolutePath(),
                "-d", moduleInfoJava.getParentFile().getAbsolutePath(), moduleInfoJava.getAbsolutePath());
        builder.redirectErrorStream(true);
        final var process = builder.start();

        new Thread(new InReader(process.getInputStream())).start();

        final var exit = process.waitFor();
        final var classFile = new File(moduleInfoJava.getParentFile(), "module-info.class");

        if (exit != 0 || !classFile.exists()) {
            throw new RuntimeException("Unable to compile module-info.java");
        }

        System.out.println("Successfully compiled module-info.java to " + classFile);
        return createModuleJar(classFile);
    }

    private File createModuleJar(File classFile) throws FileNotFoundException, IOException, InterruptedException {
        System.out.println("Creating modular jar file...");
        final var moduleJar = new File(classFile.getParentFile(), "java.base-module.jar");
        try (var out = new ZipOutputStream(new FileOutputStream(moduleJar));
                var zip = new ZipInputStream(new FileInputStream(arg.androidJar));) {

            // Write the module-info.class entry
            final var entry = new ZipEntry(classFile.getName());
            out.putNextEntry(entry);
            Files.copy(classFile.toPath(), out);
            out.closeEntry();

            // Write all the classes present in android.jar
            ZipEntry systemJarEntry;
            while ((systemJarEntry = zip.getNextEntry()) != null) {
                if (!systemJarEntry.getName().endsWith(".class")) {
                    continue;
                }

                final var newJarEntry = new ZipEntry(systemJarEntry.getName());
                final byte[] data = new byte[1024];
                int read = -1;

                out.putNextEntry(newJarEntry);
                while ((read = zip.read(data)) != -1) {
                    out.write(data, 0, read);
                }

                out.closeEntry();
            }
        }

        System.err.println("Module JAR has been succesfully created" + moduleJar);

        return createJmod(moduleJar);
    }

    private File createJmod(File moduleJar) throws IOException, InterruptedException {
        System.out.println("Generating Jmod file...");
        final var jmodOut = new File(moduleJar.getParentFile(), "java.base.jmod");
        final var builder = new ProcessBuilder(exec.jmod.getAbsolutePath(),
                "create",
                "--module-version", jlinkVersion,
                "--target-platform", "android",
                "--class-path", moduleJar.getAbsolutePath(),
                jmodOut.getAbsolutePath());
        builder.redirectErrorStream(true);

        final var process = builder.start();

        new Thread(new InReader(process.getInputStream())).start();

        final var exit = process.waitFor();

        if (exit != 0 || !jmodOut.exists()) {
            throw new RuntimeException("Unable to generate Jmod file for modular jar " + moduleJar);
        }

        System.out.println("Jmod file has successfully been generated to " + jmodOut);

        return createJdkImage(jmodOut);
    }

    private File createJdkImage(File jmodOut) throws IOException, InterruptedException {
        System.out.println("Creating JDK image...");

        final var builder = new ProcessBuilder(exec.jlink.getAbsolutePath(),
                "--module-path", jmodOut.getAbsolutePath(),
                "--add-modules", "java.base",
                "--output", arg.outputDir.getAbsolutePath(),
                "--disable-plugin", "system-modules");
        builder.redirectErrorStream(true);

        final var process = builder.start();

        new Thread(new InReader(process.getInputStream())).start();

        final var exit = process.waitFor();

        final var modulesFile = new File(arg.outputDir, "lib/modules");
        if (exit != 0 || !modulesFile.exists()) {
            throw new RuntimeException("Unable to generate JDK image. Process failed.");
        }

        System.out.println("JDK image has been successfully generated to output directory: " + arg.outputDir.getPath());

        return modulesFile;
    }

    public static void main(String[] args) throws Exception {
        try {
            final var arg = Arguments.parse(args);
            final var javaHome = findJavaHome();
            final var exec = Exec.instance(javaHome);
            final var jlinkVersion = findJlinkVersion(exec);

            System.out.println("Generating compiler module...");
            System.out.println("arg=" + arg + ", jlinkVersion=" + jlinkVersion);

            new Main(arg, exec, jlinkVersion).generate();
        } catch (Throwable th) {
            System.err.println();
            System.err.println(th.getMessage());
            System.err.println();
            System.err.println();
            printUsage();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("AndroidIDE JDK Transform");
        System.err.println(
                "This tool is used to generate the 'compiler module' (a custom JDK image) used in AndroidIDE.");
        System.err.println("The generated JDK image is used for code completions and actions in the IDE.");
        System.err.println();
        System.err.println("Usage - ");
        System.err.println(
                "    -a, --android-jar      Specify the android.jar file which will be used to generate the compiler module. Classes from this android.jar file will be included in the java.base module of the generated JDK image. REQUIRED.");
        System.err.println(
                "    -o, --output-dir       Specify the directory in which JDK image will be generated. Please note that this directory will be DELETED. This is an optional argument. By default, it is set to ''./compiler_module'.");
    }

    private static File findJavaHome() {
        final var prop = System.getProperty("java.home", null);
        if (prop != null) {
            return assertExistsAndGet(prop);
        }

        final var env = System.getenv("JAVA_HOME");
        if (env != null) {
            return assertExistsAndGet(env);
        }

        throw new RuntimeException("Cannot find JAVA_HOME");
    }

    private static File assertExistsAndGet(String prop) {
        final var file = new File(prop);
        if (!file.exists()) {
            throw new RuntimeException("JAVA_HOME is set to a directory which does not exist!");
        }
        return file;
    }

    private static String findJlinkVersion(Exec exec) throws IOException {
        final var builder = new ProcessBuilder(exec.jlink.getAbsolutePath(), "--version");
        final var process = builder.start();
        final var in = process.getInputStream();
        final var reader = new BufferedReader(new InputStreamReader(in));
        final var sb = new StringBuilder();

        int data = -1;
        while ((data = reader.read()) != -1) {
            sb.append((char) data);
        }

        assertNotBlank(sb.toString());

        return sb.toString().trim();
    }

    private static void assertNotBlank(String value) {
        if (value == null || value.trim().length() == 0) {
            throw new IllegalArgumentException(String.format("Invalid value: '%s'", value));
        }
    }

    private static void assertHasValue(final int last, int i, final String current) {
        if (i == last) {
            throw new IllegalArgumentException("No value specified for ".concat(current));
        }
    }

    static class Exec {

        final File javaHome;
        final File javac;
        final File jlink;
        final File jmod;

        public Exec(File javaHome) {
            this.javaHome = javaHome;

            final var bin = new File(javaHome, "bin");
            this.javac = new File(bin, "javac");
            this.jlink = new File(bin, "jlink");
            this.jmod = new File(bin, "jmod");
        }

        static Exec instance(File javaHome) {
            return new Exec(javaHome);
        }

        @Override
        public String toString() {
            return "Exec [javaHome=" + javaHome + ", jlink=" + jlink + ", jmod=" + jmod + "]";
        }
    }

    static class Arguments {
        File androidJar;
        File outputDir;

        static Arguments parse(String... args) {
            Objects.requireNonNull(args);

            final var arg = new Arguments();

            final var size = args.length;
            final var last = size - 1;
            for (int i = 0; i < args.length; i++) {
                final var current = args[i];
                String value = parseValue(args, current, last, i);
                if (Opts.optAndroidJar.equals(current) || Opts.optAndroidJar_short.equals(current)) {
                    arg.androidJar = new File(value);
                    i++;
                } else if (Opts.optOutDir.equals(current) || Opts.optOutDir_short.equals(current)) {
                    arg.outputDir = new File(value);
                    i++;
                }
            }

            if (arg.androidJar == null) {
                throw new IllegalArgumentException("android.jar file must be specified!");
            }

            if (arg.outputDir == null) {
                arg.outputDir = new File("./compiler_module");
            }

            if (arg.outputDir.exists()) {
                arg.outputDir.delete();
            }

            return arg;
        }

        private static String parseValue(String[] args, String current, int last, int i) {
            assertHasValue(last, i, current);
            final var value = args[++i];
            assertNotBlank(value);
            return value;
        }

        @Override
        public String toString() {
            return "Arguments [androidJar=" + androidJar + ", outputDir=" + outputDir + "]";
        }
    }

    static class Opts {
        static final String optAndroidJar = "--android-jar";
        static final String optAndroidJar_short = "-a";

        static final String optOutDir = "--output-dir";
        static final String optOutDir_short = "-o";
    }

    static class InReader implements Runnable {

        private final InputStream in;

        public InReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (var reader = new BufferedReader(new InputStreamReader(in))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    System.err.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}