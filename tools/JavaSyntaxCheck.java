import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Syntax only: does not resolve Android symbols, analyze types, or generate classes. */
public final class JavaSyntaxCheck {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java JavaSyntaxCheck.java <Java source directory>");
            System.exit(2);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("A Java 17+ runtime containing jdk.compiler is required.");
            System.exit(2);
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(Path.of(args[0]))) {
            paths = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        if (paths.isEmpty()) {
            System.err.println("No Java source files found.");
            System.exit(2);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17", "-proc:none"), null,
                    manager.getJavaFileObjectsFromPaths(paths));
            task.parse(); // Deliberately no task.analyze(), task.call(), or task.generate().
        }
        long errors = 0;
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors++;
                String file = d.getSource() == null ? "<unknown>" : d.getSource().getName();
                System.err.printf(Locale.ROOT, "%s:%d:%d: %s%n", file,
                        d.getLineNumber(), d.getColumnNumber(), d.getMessage(Locale.ROOT));
            }
        }
        if (errors > 0) System.exit(1);
        System.out.printf(Locale.ROOT,
                "PASS: parsed %d Java files using Java 17 syntax; Android types were NOT checked.%n",
                paths.size());
    }
}
