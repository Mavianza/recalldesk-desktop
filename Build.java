import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Build portabel tanpa Maven/Gradle, jalankan dari direktori proyek memakai JDK 17+. */
public final class Build {
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("test"))) {
            System.err.println("Penggunaan: java Build.java [test]");
            System.exit(2);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("Build memerlukan JDK 17+, bukan runtime Java minimal. JAR siap pakai tersedia pada dist/.");
            System.exit(2);
        }
        Path classes = Path.of("build", "classes");
        clear(classes);
        Files.createDirectories(classes);
        compile(compiler, Path.of("src"), classes, null);
        Files.createDirectories(Path.of("dist"));
        Path artifact = Path.of("dist", "recalldesk.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "recalldesk.Main");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact), manifest);
             Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                JarEntry entry = new JarEntry(name);
                entry.setTime(0);
                jar.putNextEntry(entry);
                Files.copy(path, jar);
                jar.closeEntry();
            }
        }
        System.out.println("Build berhasil: " + artifact + " (target Java 17, tanpa dependensi eksternal)");
        if (args.length == 1) {
            Path testClasses = Path.of("build", "test-classes");
            clear(testClasses);
            Files.createDirectories(testClasses);
            compile(compiler, Path.of("test"), testClasses, classes.toString());
            String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            String classpath = classes + System.getProperty("path.separator") + testClasses;
            int result = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", classpath, "recalldesk.RecallDeskTest").inheritIO().start().waitFor();
            if (result != 0) System.exit(result);
        }
    }

    private static void compile(JavaCompiler compiler, Path directory, Path destination, String classpath) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", destination.toString()));
        if (classpath != null) arguments.addAll(List.of("-classpath", classpath));
        try (Stream<Path> paths = Files.walk(directory)) {
            List<String> sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().map(Path::toString).toList();
            if (sources.isEmpty()) throw new IOException("Tidak ada source Java di " + directory);
            arguments.addAll(sources);
        }
        if (compiler.run(null, System.out, System.err, arguments.toArray(String[]::new)) != 0) {
            throw new IOException("Kompilasi gagal; lihat pesan compiler di atas.");
        }
    }

    private static void clear(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(item);
        }
    }
}
