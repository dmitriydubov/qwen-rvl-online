import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Gherkin acceptance mutation for Cucumber-JVM projects.
 *
 * <p>Follows the idea of unclebob/Acceptance-Pipeline-Specification: mutate one Examples value
 * at a time and expect the acceptance tests to fail. A mutant that still passes ("survives")
 * means the step that consumes that value does not really check it - the classic hollow step
 * definition. Only Examples values are mutated, never step text or literals, so every value
 * that matters must be an Examples parameter.
 *
 * <pre>
 * java GherkinMutator.java generate --features src/acceptanceTest/resources/features \
 *      --out build/gherkin-mutation/mutants [--only-file changed-features] [--glue-impact glue-impact]
 * java GherkinMutator.java verdict --mutants build/gherkin-mutation/mutants \
 *      --results build/gherkin-mutation/results [--glue build/classes/java/acceptanceTest --glue lib.jar ...]
 *      [--glue-file glue-classpath] [--messages build/gherkin-mutation/messages]
 * java GherkinMutator.java no-skipped --results build/test-results/acceptanceTest \
 *      --features src/acceptanceTest/resources/features [--messages build/cucumber-messages/acceptanceTest \
 *      --root . --allowlist gauntlet/config/integrity-allowlist.txt]
 * </pre>
 * {@code --only-file} limits {@code generate} to the feature files it lists: each path ended by a NUL character, the
 * form {@code git diff --name-only -z} and {@code find -print0} write - a comma, a quote or a line break stays part
 * of its name. An empty file puts no feature in scope; a listed path that names no feature file under
 * {@code --features} is an error (exit 2), never a run over nothing. With {@code --glue-impact} (Cucumber's acceptance
 * message stream plus compiled {@code base} and {@code head} glue class directories), {@code generate} widens
 * {@code --only-file} by every feature whose step definitions, hooks or parameter types changed transitively in
 * bytecode, falling back to all features when global glue or context configuration changed. {@code --glue} names one entry of the classpath
 * of the mutant run each time (a class directory or a jar, never split at a separator); {@code --glue-file} lists
 * the entries the same way as {@code --only-file}.
 * {@code generate} writes one feature file per mutant: the original Scenario Outline with its
 * {@code <placeholders>} and Background intact, and a single Examples row whose one target cell is
 * mutated - Cucumber interpolates every other value exactly as in the original run, so a non-mutated
 * column (e.g. an address holding a newline) is never distorted. For every Examples row that yields a
 * mutant it also writes two CONTROL features: the same rendering with the row left exactly as written, one that
 * runs before every mutant ({@code cN.feature}) and one that runs after them ({@code zN.feature}) - Cucumber
 * runs the files in the order of their names. Plus manifest.tsv (mutant rows {@code mN} with the line of the
 * first step that uses the mutated column, then control rows with the key {@code (control)}). Run them with
 * Cucumber, then {@code verdict} reads the JUnit XML results. Where a failure happened decides, not the
 * exception type: a kill is a failure thrown by the step DEFINITION (Cucumber cuts the stack right after its
 * frame and appends the step's own frame "at ✽.<step text>(feature:line)"), whatever was thrown - an assertion
 * library's own error, Cucumber's TableDiffException, a Mockito or WireMock verification, a timeout waiting for
 * a condition - on the first step that uses the mutated column or a later one. Not a kill: a step that failed
 * before that line (the changed value cannot have caused it); a value Cucumber rejected before any step
 * definition ran it (argument conversion, a {@code @ParameterType}/{@code @DataTableType}/{@code @DocStringType}
 * transformer - read from the compiled glue, {@code --glue} = the classpath of the mutant run -, the glue object,
 * an ambiguous step); a failure without a step frame (a hook, a fixture, the context); skipped/aborted or missing.
 * All of these are errors.
 * A kill by an undefined step for the new value and a kill by a non-assertion exception are counted
 * separately in the summary. Passed = survived.
 * A scenario that ran more than once (a retry plugin, a rerun) is judged on every attempt by these rules, whether
 * Gradle reported the attempts separately or merged ({@code reports.junitXml.mergeReruns}): the same verdict on
 * every attempt is that verdict, different verdicts are an error - for a mutant and for a control.
 * Every control must pass: it is the original row run in the mutant harness with nothing changed. A control
 * that does not pass means the mutant run itself is broken (the environment or a fixture is down, the scenario
 * depends on other scenarios or on its own name/position - or, for the control after the mutants, the row
 * cannot be repeated in one run), so failing mutants prove nothing and the verdict fails.
 * The controls prove the harness only for mutants that ran between them, one scenario at a time: with
 * {@code --messages} (Cucumber's message stream of the mutant run - NDJSON, a file or a directory of one file per
 * test JVM) the verdict checks what the run did, not how it was configured: every attempt of a mutant or a
 * control has a test case that started and finished in the stream, as many as the JUnit XML reports, no two test
 * cases of a stream overlap in time, and every mutant ran after the control before its row (cN) had finished and
 * before the control after it (zN) started, in the same stream. A missing or unreadable stream is an error.
 * {@code no-skipped} fails when the {@code --features} directory is missing, when a normal acceptance run
 * skipped scenarios, or ran none although {@code --features} holds feature files; an existing directory
 * without feature files passes without reading any results. With {@code --messages} (the acceptance run's stream)
 * it also takes a census: every scenario the feature files define - each Scenario and each Examples row of an
 * outline, in Rules too; a Background defines none - must have run; one that did not is finding F2 of its feature
 * file, which only a line {@code <path glob> F2} of {@code --allowlist} (the integrity allowlist, paths relative
 * to {@code --root}) accepts.
 * {@code generate} fails when the configured features directory does not exist (a misconfigured
 * {@code acceptance.features}; the install creates it empty), or when a scenario in scope has steps but no
 * Examples value to mutate: gherkinMutation would otherwise pass it without having checked anything.
 * A scenario with no steps of its own (empty or description-only) runs nothing under Cucumber, so it
 * yields no mutant and is not flagged vacuous.
 * Supports English and Russian keywords ("# language: ru").
 * Exit codes: 0 = OK / all killed, 1 = survivors or errors, 2 = usage or IO error.
 */
public class GherkinMutator {

    // ---------------------------------------------------------------- keywords

    record Keywords(List<String> feature, List<String> background, List<String> rule, List<String> scenario,
                    List<String> outline, List<String> examples, List<String> step) {
    }

    // Step keywords carry their trailing space (Gherkin dialect form); "* " is the universal step keyword.
    static final Map<String, Keywords> LANGUAGES = Map.of(
            "en", new Keywords(List.of("Feature", "Business Need", "Ability"), List.of("Background"), List.of("Rule"),
                    List.of("Example", "Scenario"), List.of("Scenario Outline", "Scenario Template"),
                    List.of("Examples", "Scenarios"),
                    List.of("Given ", "When ", "Then ", "And ", "But ", "* ")),
            "ru", new Keywords(List.of("Функция", "Функциональность", "Функционал", "Свойство", "Фича"),
                    List.of("Предыстория", "Контекст"), List.of("Правило"), List.of("Пример", "Сценарий"),
                    List.of("Структура сценария", "Шаблон сценария"), List.of("Примеры", "Значения"),
                    List.of("Дано ", "Допустим ", "Пусть ", "Если ", "Когда ", "То ", "Тогда ", "Затем ",
                            "И ", "А ", "Но ", "Иначе ", "Также ", "К тому же ", "* ")));

    enum Kw { FEATURE, BACKGROUND, RULE, SCENARIO, EXAMPLES }

    // ---------------------------------------------------------------- model

    static final class Examples {
        final List<String> tags;
        final int line;
        List<String> headerRaw;
        final List<List<String>> rowsRaw = new ArrayList<>();
        final List<Integer> rowLines = new ArrayList<>();

        Examples(List<String> tags, int line) {
            this.tags = tags;
            this.line = line;
        }
    }

    static final class Rule {
        final List<String> tags;
        final String header;
        final List<String> background = new ArrayList<>();

        Rule(List<String> tags, String header) {
            this.tags = tags;
            this.header = header;
        }
    }

    static final class Scenario {
        final List<String> tags;
        final String name;
        final int line;
        final Rule rule;
        final List<String> body = new ArrayList<>();
        final List<Examples> examples = new ArrayList<>();

        Scenario(List<String> tags, String name, int line, Rule rule) {
            this.tags = tags;
            this.name = name;
            this.line = line;
            this.rule = rule;
        }
    }

    static final class FeatureDoc {
        String path;
        String language = "en";
        String languageLine;
        List<String> tags = new ArrayList<>();
        String header;
        String name = "";
        final List<String> background = new ArrayList<>();
        final List<Scenario> scenarios = new ArrayList<>();
    }

    record Mutant(String id, FeatureDoc feature, Scenario scenario, Examples examples, int row, int column, String key,
                  String original, String mutated) {
    }

    /** The unmutated Examples row behind one or more mutants, run in the same harness as they are: before every
     *  mutant (cN) and after them (zN). A row that cannot be repeated in one run - the first run leaves data or
     *  state the next one trips over - fails the second control, and then its mutants' failures prove nothing. */
    record Control(String id, FeatureDoc feature, Scenario scenario, Examples examples, int row, boolean after) {
    }

    /** What one attempt of a generated scenario did: kind = passed | aborted | undefined | failed (thrown by a step
     *  definition) | rejected (Cucumber failed before a step definition ran: argument conversion, a transformer,
     *  the glue object, an ambiguous step) | error (a failure outside every step) - or inconsistent (attempts with
     *  different verdicts, see combine); type = the exception class the runner reported, or ""; line = the line of
     *  the generated feature with the failing step, or -1. */
    record Outcome(String kind, String type, int line) {
        Outcome(String kind, String type) {
            this(kind, type, -1);
        }
    }

    /** Key column of a control row in manifest.tsv (its original/mutated columns stay empty). */
    static final String CONTROL_KEY = "(control)";
    static final Pattern CONTROL_ID = Pattern.compile("[cz]\\d+");
    /** Mutants and controls are told apart by the id their feature and scenario titles start with. */
    static final Pattern RESULT_ID = Pattern.compile("(?:MUTANT (m\\d+)|CONTROL ([cz]\\d+))\\b");

    // ---------------------------------------------------------------- entry points

    public static void main(String[] args) {
        System.exit(run(args, utf8(FileDescriptor.out), utf8(FileDescriptor.err)));
    }

    static PrintStream utf8(FileDescriptor fd) {
        return new PrintStream(new FileOutputStream(fd), true, StandardCharsets.UTF_8);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: java GherkinMutator.java generate|verdict [options]");
            return 2;
        }
        Map<String, String> opts = new LinkedHashMap<>();
        List<String> glue = new ArrayList<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (args[i].equals("--glue")) {
                glue.add(args[i + 1]);          // one classpath entry per --glue: a separator could be part of its path
            } else {
                opts.put(args[i], args[i + 1]);
            }
        }
        try {
            return switch (args[0]) {
                case "generate" -> generate(Path.of(opts.getOrDefault("--features", "src/acceptanceTest/resources/features")),
                        Path.of(opts.getOrDefault("--out", "build/gherkin-mutation/mutants")),
                        opts.containsKey("--only-file") ? pathList(Path.of(opts.get("--only-file"))) : null,
                        optionalPath(opts, "--glue-impact"), out);
                case "verdict" -> verdict(Path.of(opts.getOrDefault("--mutants", "build/gherkin-mutation/mutants")),
                        Path.of(opts.getOrDefault("--results", "build/gherkin-mutation/results")),
                        glue(glue, opts.get("--glue-file")), optionalPath(opts, "--messages"), out);
                case "no-skipped" -> noSkipped(Path.of(opts.getOrDefault("--results", "build/test-results/acceptanceTest")),
                        optionalPath(opts, "--features"), new Census(optionalPath(opts, "--messages"),
                                Path.of(opts.getOrDefault("--root", ".")), optionalPath(opts, "--allowlist")), out);
                default -> {
                    err.println("GherkinMutator: unknown command " + args[0]);
                    yield 2;
                }
            };
        } catch (IOException | RuntimeException e) {
            err.println("GherkinMutator: " + e);
            return 2;
        }
    }

    static Path optionalPath(Map<String, String> opts, String option) {
        return opts.containsKey(option) ? Path.of(opts.get(option)) : null;
    }

    // ---------------------------------------------------------------- generate

    static int generate(Path featuresDir, Path outDir, List<String> only, PrintStream out) throws IOException {
        return generate(featuresDir, outDir, only, null, out);
    }

    static int generate(Path featuresDir, Path outDir, List<String> only, Path glueImpactFile, PrintStream out)
            throws IOException {
        deleteRecursively(outDir);
        Files.createDirectories(outDir);
        if (!Files.isDirectory(featuresDir)) {
            out.println("GHERKIN ERROR features directory " + featuresDir + " does not exist - acceptance.features is"
                    + " misconfigured (the install creates it empty with a .gitkeep); gherkinMutation cannot run");
            return 1;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(featuresDir)) {
            files = s.filter(p -> p.toString().endsWith(".feature")).sorted().toList();
        }
        if (only != null) {
            // A listed path that names no feature file means the list lost its paths on the way (joined with commas,
            // "Скидка, рубли.feature" came apart into two names of nothing): mutating nothing would pass on nothing.
            List<String> unknown = new ArrayList<>();
            for (String listed : only) {
                boolean found = false;
                for (Path file : files) {
                    found = found || matchesAny(file, List.of(listed));
                }
                if (!found) {
                    unknown.add(listed);
                }
            }
            if (!unknown.isEmpty()) {
                out.println("GHERKIN ERROR the scope names no feature file under " + featuresDir + ": " + unknown);
                return 2;
            }
        }
        if (glueImpactFile != null) {
            only = GlueImpact.narrow(files, only, pathList(glueImpactFile), out);
        }
        List<Mutant> mutants = new ArrayList<>();
        List<Control> controls = new ArrayList<>();
        int inScope = 0;
        int unreadable = 0;
        int vacuous = 0;
        for (Path file : files) {
            if (only != null && !matchesAny(file, only)) {
                continue;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
                lines.set(0, lines.get(0).substring(1));   // UTF-8 byte order mark
            }
            if (lines.stream().map(GherkinMutator::gherkinStrip).allMatch(l -> l.isEmpty() || l.startsWith("#"))) {
                continue;   // empty or comment-only feature file: valid for Cucumber, nothing to mutate
            }
            inScope++;
            FeatureDoc doc = parse(file, lines);
            if (doc.header == null) {
                unreadable++;
                out.println("GHERKIN ERROR " + file + ": no recognised Feature keyword (language '" + doc.language
                        + "'; supported: " + LANGUAGES.keySet() + " - add the keywords to GherkinMutator.LANGUAGES)");
                continue;
            }
            int before = mutants.size();
            collectMutants(doc, mutants, controls);
            Keywords kw = LANGUAGES.get(doc.language);
            for (Scenario sc : doc.scenarios) {
                if (stepCount(sc, kw) == 0) {
                    continue;                              // no steps of its own (empty or description-only): Cucumber
                                                           // executes nothing. Gherkin's PickleCompiler.compilePickleSteps
                                                           // returns an empty step list when the scenario has no steps,
                                                           // before prepending Background - so no step definition runs.
                }
                boolean checked = false;
                for (int mi = before; mi < mutants.size(); mi++) {
                    if (mutants.get(mi).scenario() == sc) {
                        checked = true;
                        break;
                    }
                }
                if (!checked) {
                    vacuous++;
                    out.println("GHERKIN VACUOUS " + file + ":" + sc.line + " scenario \"" + sc.name + "\" has no Examples"
                            + " value to mutate - gherkinMutation cannot check its step definitions");
                }
            }
        }
        List<String> manifest = new ArrayList<>();
        manifest.add("id\tfeature\tline\tscenario\tkey\toriginal\tmutated\tuses");
        for (Mutant m : mutants) {
            List<String> text = render(m);
            Files.write(outDir.resolve(m.id() + ".feature"), text, StandardCharsets.UTF_8);
            manifest.add(String.join("\t", m.id(), tsv(m.feature().path), String.valueOf(m.examples().rowLines.get(m.row())),
                    tsv(m.scenario().name), tsv(m.key()), tsv(m.original()), tsv(m.mutated()),
                    String.valueOf(firstUse(m, text))));
        }
        for (Control c : controls) {
            Files.write(outDir.resolve(c.id() + ".feature"), render(c), StandardCharsets.UTF_8);
            manifest.add(String.join("\t", c.id(), tsv(c.feature().path), String.valueOf(c.examples().rowLines.get(c.row())),
                    tsv(c.scenario().name), CONTROL_KEY, "", "", ""));
        }
        Files.write(outDir.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8);
        out.println("Gherkin mutation: " + mutants.size() + " mutant(s) + " + controls.size() + " control(s) from "
                + inScope + " feature file(s) in scope -> " + outDir);
        if (vacuous > 0) {
            out.println("  Every value a scenario depends on must be an Examples parameter (Scenario Outline), so that");
            out.println("  a hollow step definition shows up as a surviving mutant. The specifier rewrites the feature;");
            out.println("  the human approves it.");
        }
        return unreadable == 0 && vacuous == 0 ? 0 : 1;
    }

    static void collectMutants(FeatureDoc doc, List<Mutant> mutants, List<Control> controls) {
        Keywords kw = LANGUAGES.get(doc.language);
        for (int s = 0; s < doc.scenarios.size(); s++) {
            Scenario sc = doc.scenarios.get(s);
            if (stepCount(sc, kw) == 0) {
                continue;   // no steps: Cucumber executes nothing, so mutating its Examples proves nothing
            }
            for (int e = 0; e < sc.examples.size(); e++) {
                Examples ex = sc.examples.get(e);
                if (ex.headerRaw == null) {
                    continue;
                }
                for (int r = 0; r < ex.rowsRaw.size(); r++) {
                    List<String> row = ex.rowsRaw.get(r);
                    int before = mutants.size();
                    for (int k = 0; k < ex.headerRaw.size() && k < row.size(); k++) {
                        String key = unescape(ex.headerRaw.get(k));
                        String original = unescape(row.get(k));
                        String seed = doc.path + "|" + s + "|" + e + "|" + r + "|" + key + "|" + original;
                        String mutated = mutate(original, new Random(seed.hashCode()));
                        if (!mutated.equals(original)) {
                            mutants.add(new Mutant("m" + (mutants.size() + 1), doc, sc, ex, r, k, key, original, mutated));
                        }
                    }
                    if (mutants.size() > before) {
                        // the same row, unchanged, judged in the same run: before every mutant and after them
                        int n = controls.size() / 2 + 1;
                        controls.add(new Control("c" + n, doc, sc, ex, r, false));
                        controls.add(new Control("z" + n, doc, sc, ex, r, true));
                    }
                }
            }
        }
    }

    static List<String> render(Mutant m) {
        // the mutant's own column, not the first header with the same text: with a repeated header Cucumber
        // feeds the FIRST column to the steps, and mutating that one would "kill" a mutant of the dead column
        return render(m.feature(), m.scenario(), m.examples(), m.row(), "mutant " + m.id(), "MUTANT " + m.id(),
                " | " + m.key() + ": " + tsv(m.original()) + " -> " + tsv(m.mutated()), m.column(), m.mutated());
    }

    /** The control of a mutated row: exactly what a mutant of that row looks like, with no cell changed. */
    static List<String> render(Control c) {
        return render(c.feature(), c.scenario(), c.examples(), c.row(),
                "control " + c.id() + (c.after() ? " (runs after the mutants)" : ""), "CONTROL " + c.id(), "", -1, null);
    }

    /**
     * Line of the generated feature that holds the first step using the mutated column - in its text, its data
     * table or its doc string -, or 0 when no step uses it. Cucumber puts the line of the failing step into its
     * step frame; a step that failed on an earlier line failed for a reason the changed value cannot have caused.
     */
    static int firstUse(Mutant m, List<String> lines) {
        Keywords kw = LANGUAGES.get(m.feature().language);
        String outline = "  " + kw.outline().get(0) + ": MUTANT " + m.id() + " | ";
        String placeholder = "<" + m.key() + ">";
        int start = 0;
        while (start < lines.size() && !lines.get(start).startsWith(outline)) {
            start++;
        }
        int step = 0;
        String docString = null;
        for (int i = start + 1; i < lines.size() - 3; i++) {      // the last three lines are the Examples
            String t = gherkinStrip(lines.get(i));
            if (docString != null) {
                if (t.startsWith(docString)) {
                    docString = null;
                }
            } else if (t.startsWith("\"\"\"") || t.startsWith("```")) {
                docString = t.substring(0, 3);
            } else if (isStepLine(t, kw)) {
                step = i + 1;
            }
            if (step > 0 && lines.get(i).contains(placeholder)) {
                return step;
            }
        }
        return 0;
    }

    /**
     * One generated feature: the scenario as a single-row Scenario Outline. {@code what} names it in the header
     * comment, {@code label} starts the feature and scenario titles (verdict finds the id there), {@code change}
     * is appended to the scenario title; {@code keyIndex < 0} leaves every cell of the row as written.
     */
    static List<String> render(FeatureDoc doc, Scenario scenario, Examples ex, int rowIndex, String what, String label,
                               String change, int keyIndex, String mutated) {
        Keywords kw = LANGUAGES.get(doc.language);
        List<String> lines = new ArrayList<>();
        if (doc.languageLine != null) {
            lines.add(doc.languageLine);
        }
        // the path on one line: a line break in a file name would end the comment and start a line of Gherkin
        lines.add("# Gherkin " + what + " of " + tsv(doc.path) + " - generated, do not edit");
        lines.addAll(doc.tags);
        lines.add(featureKeyword(doc) + ": " + label + " | " + doc.name);
        lines.addAll(doc.background);
        Rule rule = scenario.rule;
        if (rule != null) {
            lines.addAll(rule.tags);
            lines.add(rule.header);
            lines.addAll(rule.background);
        }
        List<String> tags = new ArrayList<>(scenario.tags);
        tags.addAll(ex.tags);
        lines.addAll(tags);
        // Keep the Scenario Outline and its <placeholders> verbatim: Cucumber interpolates the Examples
        // values itself, exactly as in the original run. Flattening to a plain Scenario and re-escaping the
        // substituted values would distort every other column (a real newline in an address cell becomes a
        // literal \n in step text, failing an unrelated check and falsely "killing" this mutant). Only the
        // single target cell of the single target row changes; all other cells stay byte-for-byte as written.
        lines.add("  " + kw.outline().get(0) + ": " + label + " | " + scenario.name + change);
        lines.addAll(scenario.body);
        lines.add("    " + kw.examples().get(0) + ":");
        lines.add("      | " + String.join(" | ", ex.headerRaw) + " |");
        List<String> row = new ArrayList<>(ex.rowsRaw.get(rowIndex));
        if (keyIndex >= 0 && keyIndex < row.size()) {
            row.set(keyIndex, escapeCell(mutated));
        }
        lines.add("      | " + String.join(" | ", row) + " |");
        return lines;
    }

    static String featureKeyword(FeatureDoc doc) {
        String t = gherkinStrip(doc.header);
        return t.substring(0, t.indexOf(':'));
    }

    // ---------------------------------------------------------------- parser

    static FeatureDoc parse(Path file, List<String> lines) {
        FeatureDoc doc = new FeatureDoc();
        doc.path = file.toString().replace('\\', '/');
        for (String l : lines) {
            String t = gherkinStrip(l);
            if (t.isEmpty()) {
                continue;
            }
            Matcher lang = Pattern.compile("^#\\s*language\\s*:\\s*([\\w-]+)").matcher(t);
            if (lang.find()) {
                doc.language = lang.group(1);
                doc.languageLine = l;
            }
            if (!t.startsWith("#")) {
                break;
            }
        }
        Keywords kw = LANGUAGES.get(doc.language);
        if (kw == null) {
            return doc;
        }
        List<String> pendingTags = new ArrayList<>();
        List<String> block = null;
        Scenario scenario = null;
        Examples examples = null;
        Rule rule = null;
        String docStringFence = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = gherkinStrip(line);
            if (docStringFence != null) {
                if (block != null) {
                    block.add(line);
                }
                if (t.startsWith(docStringFence)) {
                    docStringFence = null;
                }
                continue;
            }
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("\"\"\"") || t.startsWith("```")) {
                docStringFence = t.startsWith("```") ? "```" : "\"\"\"";
                if (block != null) {
                    block.add(line);
                }
                continue;
            }
            if (t.startsWith("@")) {
                pendingTags.add(line);
                continue;
            }
            Kw k = keyword(t, kw);
            if (k != null) {
                String name = gherkinStrip(t.substring(t.indexOf(':') + 1));
                switch (k) {
                    case FEATURE -> {
                        doc.tags = pendingTags;
                        doc.header = line;
                        doc.name = name;
                        block = null;
                    }
                    case BACKGROUND -> {
                        block = rule != null ? rule.background : doc.background;
                        block.add(line);
                    }
                    case RULE -> {
                        rule = new Rule(pendingTags, line);
                        block = null;
                    }
                    case SCENARIO -> {
                        scenario = new Scenario(pendingTags, name, i + 1, rule);
                        doc.scenarios.add(scenario);
                        block = scenario.body;
                    }
                    case EXAMPLES -> {
                        if (scenario != null) {
                            examples = new Examples(pendingTags, i + 1);
                            scenario.examples.add(examples);
                        }
                        block = null;
                    }
                    default -> throw new IllegalStateException();
                }
                pendingTags = new ArrayList<>();
                continue;
            }
            if (t.startsWith("|") && block == null && examples != null && scenario != null
                    && !scenario.examples.isEmpty() && scenario.examples.get(scenario.examples.size() - 1) == examples) {
                List<String> cells = cells(t);
                if (examples.headerRaw == null) {
                    examples.headerRaw = cells;
                } else {
                    examples.rowsRaw.add(cells);
                    examples.rowLines.add(i + 1);
                }
                continue;
            }
            if (block != null) {
                block.add(line);
            }
        }
        return doc;
    }

    static Kw keyword(String trimmed, Keywords kw) {
        if (startsWithAny(trimmed, kw.feature())) {
            return Kw.FEATURE;
        }
        if (startsWithAny(trimmed, kw.background())) {
            return Kw.BACKGROUND;
        }
        if (startsWithAny(trimmed, kw.rule())) {
            return Kw.RULE;
        }
        if (startsWithAny(trimmed, kw.outline()) || startsWithAny(trimmed, kw.scenario())) {
            return Kw.SCENARIO;
        }
        if (startsWithAny(trimmed, kw.examples())) {
            return Kw.EXAMPLES;
        }
        return null;
    }

    static boolean startsWithAny(String trimmed, List<String> keywords) {
        for (String k : keywords) {
            if (trimmed.startsWith(k + ":")) {
                return true;
            }
        }
        return false;
    }

    /** A body line is a step when it starts with a Gherkin step keyword (which carries its trailing space).
     *  Free-text description lines are not steps; data tables and docstrings always follow a real step. */
    static boolean isStepLine(String trimmed, Keywords kw) {
        for (String s : kw.step()) {
            if (trimmed.startsWith(s)) {
                return true;
            }
        }
        return false;
    }

    static int stepCount(Scenario sc, Keywords kw) {
        int n = 0;
        for (String line : sc.body) {
            if (isStepLine(gherkinStrip(line), kw)) {
                n++;
            }
        }
        return n;
    }

    /** Gherkin's lexer treats Unicode space separators (incl. U+00A0 NBSP) as indentation, but String.trim()
     *  only strips chars <= U+0020 and keeps NBSP - a step indented with NBSP would otherwise vanish from
     *  stepCount and the scenario would be wrongly treated as having no steps (zero mutants, vacuous pass). */
    static String gherkinStrip(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && isGherkinSpace(line.charAt(start))) {
            start++;
        }
        while (end > start && isGherkinSpace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(start, end);
    }

    static boolean isGherkinSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = null;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                if (cur != null) {
                    cur.append(c).append(row.charAt(i + 1));
                }
                i++;
            } else if (c == '|') {
                if (cur != null) {
                    cells.add(gherkinStrip(cur.toString()));
                }
                cur = new StringBuilder();
            } else if (cur != null) {
                cur.append(c);
            }
        }
        return cells;
    }

    static String unescape(String cell) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == '\\' && i + 1 < cell.length()) {
                char n = cell.charAt(i + 1);
                if (n == '\\' || n == 'n' || n == '|') {
                    sb.append(n == 'n' ? '\n' : n);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String escapeCell(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
    }

    // ---------------------------------------------------------------- value mutation (APS rules)

    static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+\\.\\d+");
    static final Pattern COMMA_DECIMAL = Pattern.compile("[+-]?\\d+,\\d+");
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final Pattern TIME = Pattern.compile("(\\d{2}:\\d{2})(:\\d{2})?");
    static final Pattern DATE_TIME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})([T ])(\\d{2}:\\d{2})(:\\d{2})?(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?");
    static final Pattern SHORT_DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    static String mutate(String value, Random rnd) {
        String t = value.trim();
        if (COMMA_DECIMAL.matcher(t).matches()) {   // Russian-style decimal: 1,5 / 750,50
            return mutateDecimal(t.replace(',', '.'), rnd).replace('.', ',');
        }
        if (t.contains(",")) {
            String[] items = t.split(",", -1);
            int pick = rnd.nextInt(items.length);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                String item = items[i].trim();
                result.add(i == pick ? mutate(item, rnd) : item);
            }
            return String.join(", ", result);
        }
        String lower = t.toLowerCase();
        if (lower.equals("true") || lower.equals("false")) {
            return lower.equals("true") ? "false" : "true";
        }
        if (lower.equals("null") || lower.equals("nil") || lower.equals("none")) {
            return dither(t, rnd);
        }
        if (INTEGER.matcher(t).matches()) {
            return mutateInteger(t, rnd);
        }
        if (DECIMAL.matcher(t).matches()) {
            return mutateDecimal(t, rnd);
        }
        String temporal = mutateTemporal(t, rnd);
        if (temporal != null) {
            return temporal;
        }
        return dither(value, rnd);
    }

    static int delta(Random rnd) {
        int d = 1 + rnd.nextInt(9);
        return rnd.nextBoolean() ? d : -d;
    }

    static String mutateInteger(String t, Random rnd) {
        BigInteger v = new BigInteger(t.startsWith("+") ? t.substring(1) : t);
        int d = delta(rnd);
        BigInteger r = v.add(BigInteger.valueOf(d));
        if (v.signum() >= 0 && r.signum() < 0) {
            r = v.add(BigInteger.valueOf(Math.abs(d)));
        }
        String s = r.toString();
        String digits = t.replaceFirst("^[+-]", "");
        if (digits.length() > 1 && digits.startsWith("0") && r.signum() >= 0) {
            s = "0".repeat(Math.max(0, digits.length() - s.length())) + s;
        }
        return s;
    }

    static String mutateDecimal(String t, Random rnd) {
        BigDecimal v = new BigDecimal(t.startsWith("+") ? t.substring(1) : t);
        int scale = v.scale();
        BigDecimal step = BigDecimal.valueOf(1 + rnd.nextInt(99)).movePointLeft(scale);
        BigDecimal r = rnd.nextBoolean() ? v.add(step) : v.subtract(step);
        if (v.signum() >= 0 && r.signum() < 0) {
            r = v.add(step);
        }
        return r.setScale(scale).toPlainString();
    }

    static String mutateTemporal(String t, Random rnd) {
        try {
            if (DATE.matcher(t).matches()) {
                return LocalDate.parse(t).plusDays(delta(rnd) * 3L).toString();
            }
            Matcher tm = TIME.matcher(t);
            if (tm.matches()) {
                boolean seconds = tm.group(2) != null;
                LocalTime lt = LocalTime.parse(t);
                return seconds ? lt.plusSeconds(delta(rnd) * 7L).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        : lt.plusMinutes(delta(rnd) * 7L).format(DateTimeFormatter.ofPattern("HH:mm"));
            }
            Matcher dt = DATE_TIME.matcher(t);
            if (dt.matches()) {
                boolean seconds = dt.group(4) != null;
                LocalDateTime ldt = LocalDateTime.parse(dt.group(1) + "T" + dt.group(3) + (seconds ? dt.group(4) : ":00"));
                LocalDateTime shifted = seconds ? ldt.plusSeconds(delta(rnd) * 7L) : ldt.plusMinutes(delta(rnd) * 7L);
                String time = shifted.format(DateTimeFormatter.ofPattern(seconds ? "HH:mm:ss" : "HH:mm"));
                return shifted.toLocalDate() + dt.group(2) + time + (dt.group(5) == null ? "" : dt.group(5))
                        + (dt.group(6) == null ? "" : dt.group(6));
            }
            if (t.startsWith("P") || t.startsWith("-P")) {
                if (t.contains("T")) {
                    Duration d = Duration.parse(t);
                    Duration r = d.toMinutes() >= 1 ? d.plusMinutes(delta(rnd)) : d.plusSeconds(delta(rnd));
                    return (r.isNegative() || r.isZero() ? d.plusMinutes(Math.abs(delta(rnd))) : r).toString();
                }
                Period p = Period.parse(t);
                Period r = p.plusDays(delta(rnd));
                return (r.isNegative() ? p.plusDays(1 + rnd.nextInt(9)) : r).toString();
            }
            Matcher sd = SHORT_DURATION.matcher(t);
            if (sd.matches()) {
                long n = Long.parseLong(sd.group(1));
                long r = n + delta(rnd);
                return (r < 0 ? n + 1 + rnd.nextInt(9) : r) + sd.group(2);
            }
        } catch (RuntimeException notTemporal) {
            return null;
        }
        return null;
    }

    static String dither(String s, Random rnd) {
        if (s.isEmpty()) {
            return "x";
        }
        int[] cp = s.codePoints().toArray();       // an emoji is one code point but two chars: never split it
        for (int attempt = 0; attempt < 32; attempt++) {
            int i = rnd.nextInt(cp.length);
            int c = cp[i];
            String r = switch (rnd.nextInt(4)) {
                case 0 -> text(cp, 0, i) + similar(c, rnd) + text(cp, i + 1, cp.length);
                case 1 -> i + 1 < cp.length ? text(cp, 0, i) + text(cp, i + 1, i + 2) + text(cp, i, i + 1)
                        + text(cp, i + 2, cp.length) : s;
                case 2 -> text(cp, 0, i) + similar(c, rnd) + text(cp, i, cp.length);
                default -> cp.length > 1 ? text(cp, 0, i) + text(cp, i + 1, cp.length) : s;
            };
            if (!r.equals(s) && !r.isBlank()) {
                return r;
            }
        }
        return s + "x";
    }

    static String text(int[] codePoints, int from, int to) {
        return new String(codePoints, from, to - from);
    }

    static char similar(int c, Random rnd) {
        if (c >= 'a' && c <= 'z') {
            return (char) ('a' + rnd.nextInt(26));
        }
        if (c >= 'A' && c <= 'Z') {
            return (char) ('A' + rnd.nextInt(26));
        }
        if (c >= 'а' && c <= 'я') {
            return (char) ('а' + rnd.nextInt(32));
        }
        if (c >= 'А' && c <= 'Я') {
            return (char) ('А' + rnd.nextInt(32));
        }
        if (c >= '0' && c <= '9') {
            return (char) ('0' + rnd.nextInt(10));
        }
        return (char) ('a' + rnd.nextInt(26));
    }

    // ---------------------------------------------------------------- verdict

    static int verdict(Path mutantsDir, Path resultsDir, Glue glue, Path messages, PrintStream out) throws IOException {
        Path manifestFile = mutantsDir.resolve("manifest.tsv");
        if (!Files.isRegularFile(manifestFile)) {
            out.println("Gherkin mutation: no manifest at " + manifestFile + " - nothing to judge.");
            return 0;
        }
        Map<String, String[]> manifest = new LinkedHashMap<>();
        Map<String, String[]> controls = new LinkedHashMap<>();   // none in a manifest written before controls existed
        List<String> lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split("\t", -1);
            (CONTROL_ID.matcher(f[0]).matches() ? controls : manifest).put(f[0], f);
        }
        if (manifest.isEmpty()) {
            out.println("Gherkin mutation: no mutants (no Examples values in scope) - OK.");
            return 0;
        }
        List<String> order = new ArrayList<>();
        List<MessageStream> streams = messages == null ? List.of()
                : readStreams(messages, "MUTANT ERROR    run order: ", order);
        Map<String, List<Outcome>> attempts = readResults(resultsDir, glue, stepFailuresByScenario(streams));
        int killed = 0;
        int byUndefinedStep = 0;
        int byOtherException = 0;
        List<String> survived = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, String[]> e : manifest.entrySet()) {
            String[] f = e.getValue();
            String what = f[1] + ":" + f[2] + " \"" + f[3] + "\" " + f[4] + ": " + f[5] + " -> " + f[6];
            int uses = firstUseLine(f);
            Outcome o = combine(attempts.get(e.getKey()), attempt -> mutantVerdict(attempt, uses));
            if (o == null) {
                errors.add("MUTANT ERROR    " + e.getKey() + " not executed  " + what);
            } else if (o.kind().equals("passed")) {
                survived.add("MUTANT SURVIVED " + e.getKey() + "  " + what);
            } else if (o.kind().equals("aborted")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " skipped/aborted, not a kill  " + what);
            } else if (o.kind().equals("error")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " failed outside a step (" + thrown(o)
                        + " - hook, fixture or context), not a kill  " + what);
            } else if (o.kind().equals("inconsistent")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " ran more than once with different results (a retry or a"
                        + " rerun) - the detection is not stable, not a kill  " + what);
            } else if (o.kind().equals("rejected")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " the value never reached a step definition (" + thrown(o)
                        + ": Cucumber could not convert it, a transformer rejected it, the glue object failed or the step"
                        + " is ambiguous), not a kill  " + what);
            } else if (mutantVerdict(o, uses).equals("unused")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " failed, but no step uses <" + f[4] + "> (the scenario title"
                        + " at most) - the changed value did not cause it, not a kill  " + what);
            } else if (mutantVerdict(o, uses).equals("early")) {
                errors.add("MUTANT ERROR    " + e.getKey() + " failed on line " + o.line() + ", before the first step that"
                        + " uses <" + f[4] + "> (line " + uses + ") - the changed value did not cause it, not a"
                        + " kill  " + what);
            } else {
                killed++;
                if (o.kind().equals("undefined")) {
                    byUndefinedStep++;
                } else if (!assertionType(o.type())) {
                    byOtherException++;
                }
            }
        }
        int controlsPassed = 0;
        List<String> brokenControls = new ArrayList<>();
        for (Map.Entry<String, String[]> e : controls.entrySet()) {
            String[] f = e.getValue();
            Outcome o = combine(attempts.get(e.getKey()), Outcome::kind);
            if (o != null && o.kind().equals("passed")) {
                controlsPassed++;
            } else {
                // "MUTANT ERROR" on purpose: the Stop hook hands lines with that prefix back to the agent
                brokenControls.add("MUTANT ERROR    " + e.getKey() + " control (unmutated scenario"
                        + (e.getKey().startsWith("z") ? ", after the mutants" : "") + ") " + controlProblem(o)
                        + "  " + f[1] + ":" + f[2] + " \"" + f[3] + "\"");
            }
        }
        if (messages != null) {
            order.addAll(runOrder(streams, manifest, controls, attempts));
        }
        out.println("Gherkin mutation: " + manifest.size() + " mutant(s) - " + killed + " killed"
                + (byUndefinedStep > 0 ? " (" + byUndefinedStep + " only because no step definition matched the new value)" : "")
                + (byOtherException > 0 ? " (" + byOtherException + " by a non-assertion exception thrown in a step)" : "")
                + ", " + survived.size() + " survived, " + errors.size() + " error(s)"
                + (controls.isEmpty() ? "" : "; " + controls.size() + " control(s) - " + controlsPassed + " passed"));
        survived.forEach(out::println);
        errors.forEach(out::println);
        brokenControls.forEach(out::println);
        order.forEach(out::println);
        if (survived.isEmpty() && errors.isEmpty() && brokenControls.isEmpty() && order.isEmpty()) {
            out.println("Gherkin mutation: OK");
            return 0;
        }
        if (!survived.isEmpty()) {
            out.println("  A survivor means the scenario still passes after that Examples value changed: the step");
            out.println("  that receives the value ignores it or asserts too weakly. Fix the step definition or the");
            out.println("  production code - not the feature file. If the column truly does not matter, the");
            out.println("  specifier should remove it from the Examples (human approval required).");
        }
        if (!errors.isEmpty()) {
            out.println("  Errors: the mutant scenario did not run to a verdict - it failed outside every step (a hook, a");
            out.println("  fixture, the context), before the step that uses the changed value, or before any step");
            out.println("  definition ran (the value could not be converted or a transformer rejected it); or it was");
            out.println("  skipped, did not parse, crashed or timed out. A failure the changed value did not cause proves");
            out.println("  nothing. A rejected value: give the column a value the step can receive and check it, e.g.");
            out.println("  convert it in the step definition. See the test report.");
        }
        if (!brokenControls.isEmpty()) {
            out.println("  Controls: a control is the original Examples row run in the mutant harness with nothing changed.");
            out.println("  If it does not pass, the mutant run itself is broken (the environment or a fixture is down, or the");
            out.println("  scenario depends on other scenarios or on its own name/position), so failing mutants prove nothing.");
            out.println("  A control that runs after the mutants and fails: the row cannot be repeated in one run - what the");
            out.println("  earlier scenarios left behind (data, a shared context) decides, not the changed value. Give every");
            out.println("  scenario its own set-up (a @Before hook that resets the state, unique data per row).");
        }
        if (!order.isEmpty()) {
            out.println("  Run order: the controls prove the harness only for the mutants that ran between them, one scenario at");
            out.println("  a time - cN before the mutants of its row, zN after them. Cucumber's message stream of this run shows");
            out.println("  otherwise (or is missing), so its kills prove nothing. Something ran gherkinMutantsRun in parallel, in");
            out.println("  another order or in several JVMs: _JAVA_OPTIONS or JAVA_TOOL_OPTIONS in its environment, an argument");
            out.println("  file, a JVM argument provider, a doFirst or doLast of the project. Keep them off gherkinMutantsRun.");
        }
        return 1;
    }

    // ---------------------------------------------------------------- run order (Cucumber's message stream)

    /**
     * What the mutant run did, judged by Cucumber's message stream rather than by the settings it was started with:
     * a JVM reads options the build never lists (_JAVA_OPTIONS in the task's environment, an argument file, a provider
     * that answers differently when asked again), so a check of the arguments cannot see every way to run the
     * scenarios in parallel or in another order. Every attempt the JUnit XML reports needs a test case in the stream;
     * a stream (one test JVM: a retry round runs in a JVM of its own) must hold no two test cases at the same time;
     * every mutant of a row must have started after the control before it (cN) finished and finished before the
     * control after it (zN) started, in the same stream. Returns the errors ("MUTANT ERROR" lines: the Stop hook hands
     * them back to the agent); a missing or unreadable stream is one.
     */
    static List<String> runOrder(List<MessageStream> streams, Map<String, String[]> mutants, Map<String, String[]> controls,
                                 Map<String, List<Outcome>> attempts) {
        List<String> errors = new ArrayList<>();
        Map<String, Integer> records = new HashMap<>();
        for (MessageStream stream : streams) {
            Map<String, List<CaseRun>> byId = new LinkedHashMap<>();
            for (CaseRun run : stream.runs) {
                String id = generatedId(run.uri());
                if (run.finished() < 0) {
                    errors.add("MUTANT ERROR    run order: " + (id != null ? id : "\"" + run.name() + "\"")
                            + " started and never finished (" + stream.name + ")");
                }
                if (id != null && (mutants.containsKey(id) || controls.containsKey(id))) {
                    byId.computeIfAbsent(id, k -> new ArrayList<>()).add(run);
                    records.merge(id, 1, Integer::sum);
                }
            }
            overlaps(stream, errors);
            for (Map.Entry<String, String[]> c : controls.entrySet()) {
                String after = "z" + c.getKey().substring(1);        // generate writes the pair: cN before, zN after
                if (c.getKey().startsWith("c") && controls.containsKey(after)) {
                    bracket(stream, byId, c.getKey(), after, c.getValue(), mutants, errors);
                }
            }
        }
        // Every attempt the JUnit XML reports (a retry round, a merged rerun) has its test case in a stream: an attempt
        // without one ran in an order nobody knows. Neither of them: "not executed" says it already.
        List<String> ids = new ArrayList<>(mutants.keySet());
        ids.addAll(controls.keySet());
        for (String id : ids) {
            int reported = attempts.getOrDefault(id, List.of()).size();
            int recorded = records.getOrDefault(id, 0);
            if (recorded != reported && !streams.isEmpty()) {
                errors.add("MUTANT ERROR    run order: " + id + " ran " + reported + " time(s) by the JUnit XML and "
                        + recorded + " by the message stream - " + (recorded < reported
                        ? "the order of an attempt is unknown" : "the stream is not of the run the XML reports"));
            }
        }
        return errors;
    }

    static Map<String, List<StepFailure>> stepFailuresByScenario(List<MessageStream> streams) {
        Map<String, List<StepFailure>> map = new HashMap<>();
        for (MessageStream stream : streams) {
            for (CaseRun run : stream.runs) {
                String id = generatedId(run.uri());
                if (id != null) {
                    map.computeIfAbsent(id, k -> new ArrayList<>()).add(run.stepFailure());
                }
            }
        }
        return map;
    }

    /** Test cases of one stream that ran at the same time: sorted by start, each must start once every one before it
     *  has finished (at the same instant is not at the same time: a coarse clock gives that). */
    static void overlaps(MessageStream stream, List<String> errors) {
        List<CaseRun> runs = new ArrayList<>(stream.runs);
        runs.removeIf(run -> run.finished() < 0);
        runs.sort((a, b) -> Long.compare(a.started(), b.started()));
        int overlapping = 0;
        CaseRun latest = null;                                  // of the runs so far, the one that finished last
        for (CaseRun next : runs) {
            if (latest != null && next.started() < latest.finished() && overlapping++ < 10) {
                errors.add("MUTANT ERROR    run order: " + label(latest) + " and " + label(next) + " ran at the same time ("
                        + stream.name + ": " + label(next) + " started " + millis(latest.finished() - next.started())
                        + " ms before " + label(latest) + " finished)");
            }
            if (latest == null || next.finished() > latest.finished()) {
                latest = next;
            }
        }
        if (overlapping > 10) {
            errors.add("MUTANT ERROR    run order: " + (overlapping - 10) + " more test case(s) of " + stream.name
                    + " started before the ones before them had finished");
        }
    }

    /** The mutants of the row of control pair (cN, zN) that ran in this stream lie between cN and zN of the stream. */
    static void bracket(MessageStream stream, Map<String, List<CaseRun>> byId, String before, String after,
                        String[] row, Map<String, String[]> mutants, List<String> errors) {
        List<CaseRun> befores = byId.getOrDefault(before, List.of());
        List<CaseRun> afters = byId.getOrDefault(after, List.of());
        long beforeFinished = Long.MIN_VALUE;
        for (CaseRun run : befores) {
            beforeFinished = Math.max(beforeFinished, run.finished() < 0 ? Long.MAX_VALUE : run.finished());
        }
        long afterStarted = Long.MAX_VALUE;
        for (CaseRun run : afters) {
            afterStarted = Math.min(afterStarted, run.started());
        }
        for (Map.Entry<String, String[]> m : mutants.entrySet()) {
            if (!m.getValue()[1].equals(row[1]) || !m.getValue()[2].equals(row[2])) {
                continue;                                       // a mutant of another Examples row
            }
            for (CaseRun run : byId.getOrDefault(m.getKey(), List.of())) {
                String problem = befores.isEmpty() ? before + " did not run in the same test JVM"
                        : afters.isEmpty() ? after + " did not run in the same test JVM"
                        : run.started() < beforeFinished ? "it started before " + before + " finished"
                        : run.finished() < 0 || run.finished() > afterStarted ? after + " started before it finished"
                        : null;
                if (problem != null) {
                    errors.add("MUTANT ERROR    run order: " + m.getKey() + " did not run between its controls " + before
                            + " and " + after + " - " + problem + " (" + stream.name + ")");
                }
            }
        }
    }

    /** The id of a generated scenario (m12, c3, z3): the name of the feature file it ran from (generate writes
     *  {@code <id>.feature}); the title can hold anything the scenario's own name holds. */
    static String generatedId(String uri) {
        Matcher m = GENERATED_URI.matcher(uri == null ? "" : uri);
        return m.find() ? m.group(1) : null;
    }

    static final Pattern GENERATED_URI = Pattern.compile("/([mcz]\\d+)\\.feature$");

    static String label(CaseRun run) {
        String id = generatedId(run.uri());
        return id != null ? id : "\"" + run.name() + "\"";
    }

    static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    /** What Cucumber's message stream recorded for a failed testCaseStarted: either a hook failed ({@code inHook}),
     *  or a Gherkin step ({@code pickleStepId}) failed at {@code line} with {@code trace}. */
    record StepFailure(int line, boolean inHook, String trace) {
    }

    /** One test case a stream records: the pickle (scenario) it ran - the uri of its feature file, the line of the
     *  scenario or of its Examples row (-1 when the stream does not say), its name -, when it started and finished,
     *  in nanoseconds since the epoch (finished = -1: the stream holds no TestCaseFinished for it), and any step
     *  failure recorded by testStepFinished. */
    record CaseRun(String uri, int line, String name, long started, long finished, StepFailure stepFailure) {
    }

    /** One message stream: one Cucumber run, in one test JVM. */
    static final class MessageStream {
        final String name;
        final List<CaseRun> runs = new ArrayList<>();
        /** feature file (uri) -> line of each pickle the run compiled from it, run or not -> the pickle's name */
        final Map<String, Map<Integer, String>> pickles = new LinkedHashMap<>();

        MessageStream(String name) {
            this.name = name;
        }
    }

    /** The streams under {@code messages}: the file itself, or every *.ndjson in the directory. A missing or unreadable
     *  stream is a problem ({@code prefix} + what is wrong): what is not in a stream cannot be judged. */
    static List<MessageStream> readStreams(Path messages, String prefix, List<String> problems) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(messages)) {
            try (Stream<Path> s = Files.list(messages)) {
                files.addAll(s.filter(p -> p.toString().endsWith(".ndjson") && Files.isRegularFile(p)).sorted().toList());
            }
        } else if (Files.isRegularFile(messages)) {
            files.add(messages);
        }
        if (files.isEmpty()) {
            problems.add(prefix + "no Cucumber message stream in " + messages + " - what the run did is unknown (Cucumber"
                    + " 8 writes it only with Jackson on the acceptanceTest runtime classpath: tools.jackson.core:jackson-databind,"
                    + " or com.fasterxml.jackson.core:jackson-databind with com.fasterxml.jackson.datatype:jackson-datatype-jdk8)");
        }
        List<MessageStream> streams = new ArrayList<>();
        for (Path file : files) {
            try {
                streams.add(readStream(file));
            } catch (IOException | IllegalArgumentException | IllegalStateException | ArithmeticException e) {
                problems.add(prefix + "cannot read the message stream " + file + ": " + e.getMessage());
            }
        }
        return streams;
    }

    /**
     * One NDJSON stream of Cucumber messages (cucumber.plugin message:FILE). The envelopes this reads are those of the
     * protocol Cucumber 7 and 8 write: gherkinDocument (the AST: every node's id and line), pickle (id, uri, name,
     * steps, astNodeIds - the scenario, then the Examples row), testCase (id, pickleId, testSteps), testCaseStarted
     * (id, testCaseId, timestamp), testStepFinished (testCaseStartedId, testStepId, testStepResult), testCaseFinished
     * (testCaseStartedId, timestamp). Anything malformed or a reference to nothing throws: a stream that does not hold
     * together is no evidence of an order.
     */
    static MessageStream readStream(Path file) throws IOException {
        MessageStream stream = new MessageStream(file.getFileName().toString());
        Map<String, Integer> lines = new HashMap<>();                    // AST node id -> line, of every document
        Map<String, Map<String, Object>> pickles = new HashMap<>();
        Map<String, Object> pickleStepAstIds = new HashMap<>();          // pickleStepId -> astNodeIds
        Map<String, String> testCases = new HashMap<>();                 // test case id -> pickle id
        Map<String, String> testStepToPickleStep = new HashMap<>();      // testStepId -> pickleStepId (null for a hook)
        Map<String, Map<String, Object>> started = new LinkedHashMap<>();
        Map<String, String> firstFailedPickleStep = new HashMap<>();
        Map<String, String> firstFailedTrace = new HashMap<>();
        Set<String> hookFailed = new HashSet<>();
        Map<String, Long> finished = new HashMap<>();
        int number = 0;
        int envelopes = 0;
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> envelope;
            try {
                envelope = Json.object(Json.parse(text), "envelope");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + number + ": " + e.getMessage() + " (two test JVMs that write"
                        + " one stream at once - forks in parallel - leave it like this)", e);
            }
            envelopes++;
            for (Map.Entry<String, Object> e : envelope.entrySet()) {
                switch (e.getKey()) {
                    case "gherkinDocument" -> astLines(e.getValue(), lines);
                    case "pickle" -> {
                        Map<String, Object> pickle = Json.object(e.getValue(), "pickle");
                        pickles.put(Json.text(pickle, "id"), pickle);
                        if (pickle.get("steps") instanceof List<?> steps) {
                            for (Object stepObj : steps) {
                                Map<String, Object> ps = Json.object(stepObj, "pickle.steps");
                                if (ps.get("id") instanceof String psId) {
                                    pickleStepAstIds.put(psId, ps.get("astNodeIds"));
                                }
                            }
                        }
                    }
                    case "testCase" -> {
                        Map<String, Object> testCase = Json.object(e.getValue(), "testCase");
                        testCases.put(Json.text(testCase, "id"), Json.text(testCase, "pickleId"));
                        if (testCase.get("testSteps") instanceof List<?> steps) {
                            for (Object stepObj : steps) {
                                Map<String, Object> ts = Json.object(stepObj, "testCase.testSteps");
                                if (ts.get("id") instanceof String tsId) {
                                    testStepToPickleStep.put(tsId, ts.get("pickleStepId") instanceof String psId ? psId : null);
                                }
                            }
                        }
                    }
                    case "testCaseStarted" -> {
                        Map<String, Object> start = Json.object(e.getValue(), "testCaseStarted");
                        started.put(Json.text(start, "id"), start);
                    }
                    case "testStepFinished" -> {
                        Map<String, Object> sf = Json.object(e.getValue(), "testStepFinished");
                        String runId = Json.text(sf, "testCaseStartedId");
                        String stepId = Json.text(sf, "testStepId");
                        Map<String, Object> res = Json.object(sf.get("testStepResult"), "testStepResult");
                        if ("FAILED".equals(res.get("status"))) {
                            String psId = testStepToPickleStep.get(stepId);
                            if (psId == null) {
                                hookFailed.add(runId);
                            } else if (!hookFailed.contains(runId) && !firstFailedPickleStep.containsKey(runId)) {
                                firstFailedPickleStep.put(runId, psId);
                                String msg = res.get("message") instanceof String m ? m : "";
                                String excTrace = res.get("exception") instanceof Map<?, ?> exc
                                        && exc.get("stackTrace") instanceof String st ? st : "";
                                firstFailedTrace.put(runId, msg + (excTrace.isEmpty() ? "" : "\n" + excTrace));
                            }
                        }
                    }
                    case "testCaseFinished" -> {
                        Map<String, Object> finish = Json.object(e.getValue(), "testCaseFinished");
                        finished.put(Json.text(finish, "testCaseStartedId"), Json.nanos(finish, "timestamp"));
                    }
                    default -> {
                    }
                }
            }
        }
        if (envelopes == 0) {
            // Cucumber opens the file before it creates the plugin: a run that failed to start leaves it empty
            throw new IllegalStateException("it holds no message - Cucumber did not start its run (Cucumber 8 writes the"
                    + " stream only with Jackson on the acceptanceTest runtime classpath: tools.jackson.core:jackson-databind,"
                    + " or com.fasterxml.jackson.core:jackson-databind with com.fasterxml.jackson.datatype:jackson-datatype-jdk8)");
        }
        for (Map<String, Object> pickle : pickles.values()) {
            stream.pickles.computeIfAbsent(Json.text(pickle, "uri"), k -> new TreeMap<>())
                    .put(pickleLine(pickle, lines), Json.text(pickle, "name"));
        }
        for (Map.Entry<String, Map<String, Object>> e : started.entrySet()) {
            String runId = e.getKey();
            String pickleId = testCases.get(Json.text(e.getValue(), "testCaseId"));
            Map<String, Object> pickle = pickleId == null ? null : pickles.get(pickleId);
            if (pickle == null) {
                throw new IllegalStateException("test case " + runId + " started for a test case or pickle the"
                        + " stream does not define");
            }
            StepFailure sf = hookFailed.contains(runId) ? new StepFailure(-1, true, "")
                    : firstFailedPickleStep.containsKey(runId)
                    ? new StepFailure(stepAstLine(pickleStepAstIds.get(firstFailedPickleStep.get(runId)), lines),
                            false, firstFailedTrace.getOrDefault(runId, ""))
                    : null;
            stream.runs.add(new CaseRun(Json.text(pickle, "uri"), pickleLine(pickle, lines), Json.text(pickle, "name"),
                    Json.nanos(e.getValue(), "timestamp"), finished.getOrDefault(runId, -1L), sf));
        }
        return stream;
    }

    /** The line of a pickleStep in its feature file: the first AST node id of the step (PickleCompiler puts the Step
     *  id first and any Examples row id second). */
    static int stepAstLine(Object astNodeIds, Map<String, Integer> lines) {
        if (!(astNodeIds instanceof List<?> ids) || ids.isEmpty()) {
            return -1;
        }
        return lines.getOrDefault(Json.string(ids.get(0), "astNodeIds"), -1);
    }

    /** The line of a pickle: of its Examples row (the last AST node it comes from), else of its scenario; -1 when the
     *  stream holds no document with that node. */
    static int pickleLine(Map<String, Object> pickle, Map<String, Integer> lines) {
        List<Object> ids = Json.list(pickle.get("astNodeIds"), "astNodeIds");
        return ids.isEmpty() ? -1 : lines.getOrDefault(Json.string(ids.get(ids.size() - 1), "astNodeIds"), -1);
    }

    /** Every node of a Gherkin document that has an id and a location: its line. The shape of the tree (feature, rule,
     *  scenario, examples, table rows) is not assumed, so a version that nests it differently loses no node. */
    static void astLines(Object node, Map<String, Integer> lines) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("id") instanceof String id && map.get("location") instanceof Map<?, ?> location
                    && location.get("line") instanceof BigDecimal line) {
                lines.put(id, line.intValueExact());
            }
            map.values().forEach(child -> astLines(child, lines));
        } else if (node instanceof List<?> list) {
            list.forEach(child -> astLines(child, lines));
        }
    }

    /** A reader for one line of NDJSON - the tool has no dependencies. Objects become maps (in their order), arrays
     *  lists, numbers BigDecimal, true/false Boolean, null {@link #NULL}. Anything that is not JSON throws. */
    static final class Json {
        static final Object NULL = new Object();
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            Object value = json.value(0);
            json.space();
            if (json.at < text.length()) {
                throw json.error("text after the value");
            }
            return value;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(Object value, String what) {
            if (value instanceof Map<?, ?>) {
                return (Map<String, Object>) value;
            }
            throw new IllegalArgumentException(what + " is not a JSON object");
        }

        @SuppressWarnings("unchecked")
        static List<Object> list(Object value, String what) {
            if (value instanceof List<?>) {
                return (List<Object>) value;
            }
            throw new IllegalArgumentException(what + " is not a JSON array");
        }

        static String string(Object value, String what) {
            if (value instanceof String s) {
                return s;
            }
            throw new IllegalArgumentException(what + " is not a JSON string");
        }

        static String text(Map<String, Object> object, String key) {
            return string(object.get(key), key);
        }

        /** A Cucumber timestamp {"seconds": s, "nanos": n} in nanoseconds since the epoch. */
        static long nanos(Map<String, Object> object, String key) {
            Map<String, Object> timestamp = object(object.get(key), key);
            if (!(timestamp.get("seconds") instanceof BigDecimal seconds) || !(timestamp.get("nanos") instanceof BigDecimal nanos)) {
                throw new IllegalArgumentException(key + " has no seconds and nanos");
            }
            return Math.addExact(Math.multiplyExact(seconds.longValueExact(), 1_000_000_000L), nanos.longValueExact());
        }

        private Object value(int depth) {
            if (depth > 512) {
                throw error("nested too deeply");
            }
            space();
            if (at >= text.length()) {
                throw error("a value is missing");
            }
            char c = text.charAt(at);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                at++;
                space();
                if (skip('}')) {
                    return map;
                }
                do {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    map.put(key, value(depth + 1));
                    space();
                } while (skip(','));
                expect('}');
                return map;
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                at++;
                space();
                if (skip(']')) {
                    return list;
                }
                do {
                    list.add(value(depth + 1));
                    space();
                } while (skip(','));
                expect(']');
                return list;
            }
            if (c == '"') {
                return string();
            }
            for (String word : List.of("true", "false", "null")) {
                if (text.startsWith(word, at)) {
                    at += word.length();
                    return word.equals("null") ? NULL : Boolean.valueOf(word);
                }
            }
            Matcher number = NUMBER.matcher(text).region(at, text.length());
            if (!number.lookingAt()) {
                throw error("not a JSON value");
            }
            at = number.end();
            return new BigDecimal(number.group());
        }

        static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("a control character in a string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length() || !text.substring(at, at + 4).matches("[0-9a-fA-F]{4}")) {
                            throw error("a broken \\u escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw error("an unknown escape \\" + e);
                }
            }
            throw error("an unterminated string");
        }

        private void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        /** Moves past {@code c} when it is next. */
        private boolean skip(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!skip(c)) {
                throw error("'" + c + "' expected");
            }
        }

        private IllegalArgumentException error(String what) {
            return new IllegalArgumentException("not JSON at column " + (at + 1) + ": " + what);
        }
    }

    static String thrown(Outcome o) {
        return o.type().isEmpty() ? "unknown exception" : o.type();
    }

    /** Why a control did not pass ({@code null} = no result for it at all). */
    static String controlProblem(Outcome o) {
        if (o == null) {
            return "was not executed";
        }
        return switch (o.kind()) {
            case "aborted" -> "was skipped/aborted";
            case "undefined" -> "has a step without a matching step definition";
            case "failed" -> "failed in a step with " + thrown(o);
            case "rejected" -> "failed before a step definition ran (" + thrown(o) + ")";
            case "inconsistent" -> "ran more than once with different results";
            default -> "failed outside a step (" + thrown(o) + " - hook, fixture or context)";
        };
    }

    /** Cucumber reports undefined steps as aborted, which Gradle shows as skipped - not as failed. */
    static int noSkipped(Path resultsDir, Path featuresDir, Census census, PrintStream out) throws IOException {
        if (featuresDir != null) {
            if (!Files.isDirectory(featuresDir)) {
                out.println("Acceptance: FAILED - features directory " + featuresDir + " does not exist - acceptance.features"
                        + " is misconfigured (the install creates it empty with a .gitkeep); a missing dir is not an empty run");
                return 1;
            }
            if (!hasFeatureFiles(featuresDir)) {
                out.println("Acceptance: no feature files in " + featuresDir + " - nothing to check (older results ignored)");
                return 0;
            }
        }
        List<String> skipped = new ArrayList<>();
        int total = 0;
        for (Element tc : testcases(resultsDir)) {
            total++;
            for (Node n = tc.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals("skipped")) {
                    skipped.add("ACCEPTANCE SKIPPED " + tc.getAttribute("classname") + " > " + tc.getAttribute("name"));
                }
            }
        }
        if (total == 0 && featuresDir != null) {
            out.println("Acceptance: FAILED - feature files exist in " + featuresDir + ", but no scenario ran (no results in "
                    + resultsDir + "). An empty run proves nothing: check cucumber.features, the glue and the engine.");
            return 1;
        }
        List<String> notRun = new ArrayList<>();
        String counted = census.messages() == null || featuresDir == null ? null : census(featuresDir, census, notRun);
        skipped.forEach(out::println);
        notRun.forEach(out::println);
        if (counted != null) {
            out.println(counted);
        }
        if (skipped.isEmpty() && notRun.isEmpty()) {
            out.println("Acceptance: " + total + " scenario(s), none skipped - OK");
            return 0;
        }
        if (!skipped.isEmpty()) {
            out.println("Acceptance: FAILED - " + skipped.size() + " scenario(s) skipped. Cucumber reports undefined or");
            out.println("  pending steps as skipped, so they would otherwise pass silently. Implement the missing step");
            out.println("  definitions (run with -Dcucumber.plugin=pretty to see the snippets).");
        }
        if (notRun.stream().anyMatch(line -> line.startsWith("ACCEPTANCE F2 "))) {
            out.println("Acceptance: FAILED - scenarios the feature files define did not run (finding F2). Something left them");
            out.println("  out of the acceptance run: a tag or name filter (cucumber.filter.tags/name in junit-platform.properties");
            out.println("  or as -D, includeTags/excludeTags of the test platform), a filter of the task, a narrower");
            out.println("  cucumber.features. Remove it. Only the human can accept one that must stay, per feature file, in");
            out.println("  gauntlet/config/integrity-allowlist.txt: <feature file path glob> F2.");
        }
        if (notRun.stream().anyMatch(line -> line.startsWith("ACCEPTANCE ERROR "))) {
            out.println("Acceptance: FAILED - which scenarios ran is unknown without Cucumber's message stream of this run.");
        }
        return 1;
    }

    /** Where acceptanceCheck learns what the acceptance run ran, and who may accept a scenario that did not run:
     *  {@code messages} = Cucumber's message stream of the run (null: no census), {@code root} = the directory the
     *  paths of the allowlist are relative to (the project), {@code allowlist} = the integrity allowlist. */
    record Census(Path messages, Path root, Path allowlist) {
    }

    /**
     * The census of an acceptance run: every scenario the feature files define must have run. Defined is what Cucumber
     * compiles a feature file into - a Scenario is one, a Scenario Outline one per Examples row (none without rows), in
     * Rules too, a Background none -, by this tool's own reading of every feature file under {@code featuresDir} and
     * by the pickles Cucumber's message stream lists for the files the run loaded: a filter of tags, of names or of the
     * test platform drops pickles of a file Cucumber parsed, a narrower cucumber.features never loads the file at all.
     * Ran is a test case that started and finished in a stream of the run. A defined scenario that did not run is
     * finding F2 of its feature file, unless the allowlist accepts that file for F2. Adds the findings - or, when a
     * stream is missing or cannot be read, that problem - to {@code notRun}; returns the census line (null without one).
     */
    static String census(Path featuresDir, Census census, List<String> notRun) throws IOException {
        List<String> problems = new ArrayList<>();
        List<MessageStream> streams = readStreams(census.messages(), "ACCEPTANCE ERROR ", problems);
        if (!problems.isEmpty()) {
            notRun.addAll(problems);
            return null;
        }
        Map<Path, Map<Integer, String>> compiled = new HashMap<>();      // feature file -> line -> name, by Cucumber
        Map<Path, Set<Integer>> ran = new HashMap<>();
        for (MessageStream stream : streams) {
            for (Map.Entry<String, Map<Integer, String>> e : stream.pickles.entrySet()) {
                Path file = featureFile(e.getKey());
                if (file != null) {
                    compiled.computeIfAbsent(file, k -> new TreeMap<>()).putAll(e.getValue());
                }
            }
            for (CaseRun run : stream.runs) {
                Path file = featureFile(run.uri());
                if (file != null && run.finished() >= 0) {
                    ran.computeIfAbsent(file, k -> new HashSet<>()).add(run.line());
                }
            }
        }
        List<Allow> allows = readAllowlist(census.allowlist());
        List<Path> files;
        try (Stream<Path> s = Files.walk(featuresDir)) {
            files = s.filter(p -> p.toString().endsWith(".feature") && Files.isRegularFile(p)).sorted().toList();
        }
        int defined = 0;
        int done = 0;
        int counted = 0;
        int accepted = 0;
        for (Path file : files) {
            if (!hasGherkin(file)) {
                continue;
            }
            counted++;
            Path key = realPath(file);
            Map<Integer, String> own = scenarios(file);
            Map<Integer, String> scenarios = new TreeMap<>(own == null ? Map.of() : own);
            scenarios.putAll(compiled.getOrDefault(key, Map.of()));     // Cucumber's own names
            Set<Integer> fileRan = ran.getOrDefault(key, Set.of());
            List<Map.Entry<Integer, String>> missing = scenarios.entrySet().stream()
                    .filter(e -> !fileRan.contains(e.getKey())).toList();
            defined += scenarios.size();
            done += scenarios.size() - missing.size();
            boolean unknown = own == null && !compiled.containsKey(key);  // neither this tool nor Cucumber read it
            if (missing.isEmpty() && !unknown) {
                continue;
            }
            String rel = relative(census.root(), file);
            if (allowed(rel, "F2", allows)) {
                accepted += missing.size();
                continue;
            }
            // Cucumber lists nothing of a file without scenarios either: a German draft with only a Funktionalität
            notRun.add("ACCEPTANCE F2 " + rel + "  " + (unknown ? "Cucumber listed no scenario of it (it did not load"
                    + " the file, or the file defines none), and its language is not one GherkinMutator reads: which"
                    + " scenarios it defines is unknown"
                    : (scenarios.size() - missing.size()) + " of " + scenarios.size() + " scenario(s) ran"
                    + (compiled.containsKey(key) ? "" : " - Cucumber did not load the file")));
            for (int i = 0; i < missing.size() && i < 20; i++) {
                notRun.add("ACCEPTANCE F2 " + rel + ":" + missing.get(i).getKey() + "  did not run  |  "
                        + missing.get(i).getValue());
            }
            if (missing.size() > 20) {
                notRun.add("ACCEPTANCE F2 " + rel + "  ... and " + (missing.size() - 20) + " more");
            }
        }
        return "Acceptance census: " + done + " of " + defined + " scenario(s) in " + counted + " feature file(s) ran"
                + (accepted > 0 ? "; " + accepted + " did not run, accepted by the integrity allowlist (F2)" : "");
    }

    /** The scenarios a feature file defines, by this tool's reading: the line of each Scenario, or of each Examples row
     *  of one with Examples, and its name. null when the tool cannot read the file (a language it has no keywords of). */
    static Map<Integer, String> scenarios(Path file) {
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
        if (!lines.isEmpty() && lines.get(0).startsWith("﻿")) {
            lines.set(0, lines.get(0).substring(1));
        }
        FeatureDoc doc = parse(file, lines);
        if (doc.header == null) {
            return null;
        }
        Map<Integer, String> scenarios = new TreeMap<>();
        for (Scenario sc : doc.scenarios) {
            if (sc.examples.isEmpty()) {
                scenarios.put(sc.line, sc.name);    // Gherkin compiles an outline without Examples as one scenario
            }
            for (Examples ex : sc.examples) {
                for (int row : ex.rowLines) {
                    scenarios.put(row, sc.name);
                }
            }
        }
        return scenarios;
    }

    /** The feature file a pickle's uri names (file: uris only - a classpath: one names no file under --features). */
    static Path featureFile(String uri) {
        if (uri == null || !uri.startsWith("file:")) {
            return null;
        }
        try {
            return realPath(Path.of(URI.create(uri)));
        } catch (RuntimeException notAFile) {
            return null;
        }
    }

    static Path realPath(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException | RuntimeException gone) {
            return file.toAbsolutePath().normalize();
        }
    }

    static String relative(Path root, Path file) {
        try {
            return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException otherRoot) {
            return file.toAbsolutePath().toString().replace('\\', '/');
        }
    }

    /** One line of the integrity allowlist: a path glob relative to the project and a rule id. */
    record Allow(PathMatcher matcher, String ruleId) {
    }

    /** The integrity allowlist, read as IntegrityCheck reads it: "path-glob RULE-ID", a # starts a comment. */
    static List<Allow> readAllowlist(Path file) throws IOException {
        List<Allow> list = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return list;
        }
        // lenient, as IntegrityCheck reads it: a stray byte in a comment must not drop the whole file
        for (String raw : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList()) {
            String line = raw.replaceAll("#.*$", "").trim();
            String[] parts = line.split("\\s+");
            if (!line.isEmpty() && parts.length >= 2) {
                list.add(new Allow(FileSystems.getDefault().getPathMatcher("glob:" + parts[0]), parts[1]));
            }
        }
        return list;
    }

    static boolean allowed(String path, String ruleId, List<Allow> allows) {
        for (Allow a : allows) {
            if (a.ruleId().equals(ruleId) && a.matcher().matches(Path.of(path))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasFeatureFiles(Path featuresDir) throws IOException {
        if (!Files.isDirectory(featuresDir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(featuresDir)) {
            return s.filter(p -> p.toString().endsWith(".feature")).anyMatch(GherkinMutator::hasGherkin);
        }
    }

    /** Empty and comment-only feature files are valid for Cucumber but hold no specification. */
    static boolean hasGherkin(Path featureFile) {
        try {
            return Files.readAllLines(featureFile, StandardCharsets.UTF_8).stream()
                    .map(l -> gherkinStrip(l.replace("\uFEFF", ""))).anyMatch(l -> !l.isEmpty() && !l.startsWith("#"));
        } catch (IOException unreadable) {
            return true;   // unreadable is not proof of emptiness
        }
    }

    static List<Element> testcases(Path resultsDir) throws IOException {
        List<Element> list = new ArrayList<>();
        if (!Files.isDirectory(resultsDir)) {
            return list;
        }
        List<Path> xmls;
        try (Stream<Path> s = Files.walk(resultsDir)) {
            xmls = s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try {
            // Gradle's JUnit XML declares no DOCTYPE. One that does is no report of Gradle's, and an external entity in
            // it would read any file the build can read into the verdict (XXE): such a file is not read at all.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException e) {
            throw new IOException("the XML parser cannot refuse a DOCTYPE: " + e.getMessage(), e);
        }
        // JDK 24 and newer count every reference to a built-in entity (&lt;, &quot;) against
        // jdk.xml.maxGeneralEntitySizeLimit, 100,000 per document: a large result file full of escaped names stopped
        // the tool. No entity of the file's own can exist (a DOCTYPE is refused), so the size limits are lifted.
        for (String limit : List.of("maxGeneralEntitySizeLimit", "totalEntitySizeLimit")) {
            try {
                f.setAttribute("http://www.oracle.com/xml/jaxp/properties/" + limit, "0");
            } catch (IllegalArgumentException olderParser) {   // a parser without the limit needs no lifting
            }
        }
        for (Path xml : xmls) {
            try {
                Document doc = f.newDocumentBuilder().parse(xml.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    list.add((Element) cases.item(i));
                }
            } catch (Exception unreadable) {
                // Gradle writes every TEST-*.xml itself: one that does not read (a DOCTYPE, cut off) would drop its
                // scenarios from the verdict silently. Another XML file in the directory is no report - ignored.
                if (xml.getFileName().toString().startsWith("TEST-")) {
                    throw new IOException("cannot read the test results " + xml + ": " + unreadable.getMessage(), unreadable);
                }
            }
        }
        return list;
    }

    /**
     * Every attempt of every generated scenario, in report order: one per scenario in an ordinary run. A retry (the
     * test-retry plugin, a rerun) runs a scenario again, and Gradle reports the attempts in one of two shapes: each
     * attempt as a {@code <testcase>} of its own, or - with {@code reports.junitXml.mergeReruns}, Surefire's shape -
     * the attempts of a test that failed merged into one {@code <testcase>}. A merged test that passed in the end
     * holds a {@code <flakyFailure>}/{@code <flakyError>} per failed attempt and no element for the passing one
     * ({@code <skipped>} when the last attempt was skipped); one that failed every time holds {@code <failure>} or
     * {@code <error>} for the first attempt and {@code <rerunFailure>}/{@code <rerunError>} for each later one.
     * Reading only {@code <failure>} took a control that failed and then passed for a passed control.
     */
    static Map<String, List<Outcome>> readResults(Path resultsDir, Glue glue,
                                                  Map<String, List<StepFailure>> streamFailures) throws IOException {
        Map<String, List<Outcome>> attempts = new LinkedHashMap<>();
        for (Element tc : testcases(resultsDir)) {
            Matcher m = RESULT_ID.matcher(tc.getAttribute("name") + " " + tc.getAttribute("classname"));
            if (!m.find()) {
                continue;
            }
            String id = m.group(1) != null ? m.group(1) : m.group(2);
            List<Outcome> list = attempts.computeIfAbsent(id, k -> new ArrayList<>());
            List<StepFailure> sfs = streamFailures.getOrDefault(id, List.of());
            boolean passedLast = true;              // no element stands for the last attempt: it passed
            for (Node n = tc.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e) {
                    switch (e.getTagName()) {
                        // The stage decides, not the element name: Gradle writes every failed scenario as
                        // <failure>, Surefire-style writers use <error> for a non-assertion exception.
                        case "failure", "error", "rerunFailure", "rerunError" -> {
                            StepFailure sf = list.size() < sfs.size() ? sfs.get(list.size()) : null;
                            list.add(failureOutcome(e, glue, id, sf));
                            passedLast = false;
                        }
                        case "flakyFailure", "flakyError" -> {
                            StepFailure sf = list.size() < sfs.size() ? sfs.get(list.size()) : null;
                            list.add(failureOutcome(e, glue, id, sf));
                        }
                        case "skipped" -> {
                            list.add(new Outcome("aborted", ""));
                            passedLast = false;
                        }
                        default -> {
                        }
                    }
                }
            }
            if (passedLast) {
                list.add(new Outcome("passed", ""));
            }
        }
        return attempts;
    }

    /**
     * One outcome from all attempts of a scenario ({@code null} = none ran). Each attempt is judged on its own by
     * {@code verdictOf} - the rules of a single run -, then the same verdict on every attempt is that verdict (a
     * mutant killed on each attempt stays killed; the first attempt stands for it), and different verdicts are
     * "inconsistent": a detection that does not repeat is no kill, a control that does not repeat is broken.
     * Comparing kinds is not enough: two failures in a step are a kill and no kill when only one of them is on or
     * after the first step that uses the changed value - keeping either one made the verdict depend on the order.
     */
    static Outcome combine(List<Outcome> attempts, Function<Outcome, String> verdictOf) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        Outcome first = attempts.get(0);
        for (Outcome o : attempts) {
            if (!verdictOf.apply(o).equals(verdictOf.apply(first))) {
                return new Outcome("inconsistent", first.type().isEmpty() ? o.type() : first.type());
            }
        }
        return first;
    }

    /** The verdict one attempt of a mutant gives: "killed" (a failure thrown by a step definition on or after the
     *  first step that uses the changed value, or an undefined step), "early" (thrown by a step before that one),
     *  "unused" (a failure, while no step uses the changed column: the value cannot have caused it), or its kind -
     *  "passed" (survived), "aborted", "error", "rejected". firstUse is -1 when the manifest does not say. */
    static String mutantVerdict(Outcome o, int firstUse) {
        return switch (o.kind()) {
            case "failed" -> firstUse == 0 ? "unused"
                    : firstUse > 0 && o.line() > 0 && o.line() < firstUse ? "early" : "killed";
            case "undefined" -> "killed";
            default -> o.kind();
        };
    }

    /**
     * A failed scenario counts as "failed" (a kill for a mutant) only with positive evidence that a step DEFINITION
     * threw - whatever its type: a hard-coded list of assertion classes can never be complete (Cucumber's own
     * TableDiffException, Mockito and WireMock verifications, Awaitility timeouts are none of
     * java.lang.AssertionError's usual names). Primary evidence is the step frame "at ✽.<step text>(feature:line)"
     * that Cucumber appends after cutting the stack right behind the frame of the definition that threw. Two runtime
     * paths strip "✽." from JUnit XML even when a Gherkin step failed: (1) Awaitility.untilAsserted (or another pool)
     * builds an AssertionError on a worker thread whose stack lacks the step method, so Cucumber's StackManipulation
     * appends no "✽."; (2) Gradle 8.14's DefaultThrowableToTestFailureMapper walks getCause() and unwraps an outer
     * step exception (e.g. ConditionTimeoutException with "✽.") to its inner AssertionError cause. When XML has no
     * "✽." and no runner frame, Cucumber's own NDJSON stream (testStepFinished on a pickleStepId with no hook failure)
     * proves whether a Gherkin step failed and on which feature line, while transformer and runner checks still reject
     * conversions that failed before the step body ran.
     */
    static Outcome failureOutcome(Element failure, Glue glue, String id, StepFailure sf) throws IOException {
        String type = failure.getAttribute("type");
        if (undefinedStep(failure)) {
            return new Outcome("undefined", type);
        }
        String trace = trace(failure);
        int step = trace.indexOf("✽.");
        if (step >= 0) {
            String before = trace.substring(0, step);
            if (before.contains(RUNNER) || glue != null && glue.isTransformer(lastFrame(before))) {
                return new Outcome("rejected", type);
            }
            Matcher line = stepLine(id).matcher(trace);
            return new Outcome("failed", type, line.find(step) ? Integer.parseInt(line.group(1)) : -1);
        }
        if (trace.contains(RUNNER)) {
            return new Outcome("rejected", type);
        }
        if (sf != null && !sf.inHook()) {
            int sfStep = sf.trace().indexOf("✽.");
            if (sfStep >= 0) {
                String before = sf.trace().substring(0, sfStep);
                if (before.contains(RUNNER) || glue != null && glue.isTransformer(lastFrame(before))) {
                    return new Outcome("rejected", type);
                }
                Matcher line = stepLine(id).matcher(sf.trace());
                return new Outcome("failed", type, line.find(sfStep) ? Integer.parseInt(line.group(1)) : sf.line());
            }
            if (sf.trace().contains(RUNNER) || glue != null && glue.hasTransformerFrame(trace + "\n" + sf.trace())) {
                return new Outcome("rejected", type);
            }
            return new Outcome("failed", type, sf.line());
        }
        return new Outcome("error", type);
    }

    /** The stack trace of one failed attempt: the text of {@code <failure>}/{@code <error>}; a flaky or rerun
     *  element keeps it in a {@code <stackTrace>} child (next to that attempt's output, which is no trace). */
    static String trace(Element failure) {
        for (Node n = failure.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals("stackTrace")) {
                return e.getTextContent();
            }
        }
        return failure.getTextContent();
    }

    /** Frames of Cucumber's own runner: in a step's stack they are cut away, unless no step definition ran. */
    static final String RUNNER = "io.cucumber.core.runner.";
    static final Pattern LAMBDA_METHOD = Pattern.compile("^lambda\\$(.+)\\$\\d+$");
    /** The line of the step frame "✽.<step text>(file:///.../m1.feature:12)" of scenario {@code id}: the first
     *  "/<id>.feature:<line>)" after "✽." that ends a line of the trace. The step text is the step as it ran - an
     *  Examples value brings its colons, parentheses, line breaks and even a "see a.feature:3)" of its own into it -,
     *  so the frame is known by the name of the file generated for the scenario, not by the end of the line it
     *  starts on; what follows the frame varies ("Caused by:", "Suppressed:", Gradle's "Cause 1:"). */
    static Pattern stepLine(String id) {
        return Pattern.compile("/" + Pattern.quote(id) + "\\.feature:(\\d{1,9})\\)(?=[ \\t]*(?:\\R|\\z))");
    }
    /** A stack frame line "at [loader/[module]/]pkg.Class.method(File.java:12)". The location is its last parenthesized
     *  part: a JVM name can hold spaces and parentheses (Kotlin `the total (EUR) is`, a Groovy "string" method). */
    static final Pattern FRAME = Pattern.compile("(?m)^\\s*at ([^\\n]*\\))");

    /** Where a frame points: the binary name of its class ("pkg.Outer$Inner", "pkg.FileKt"), the method, the line
     *  (-1 when the class has no line numbers). */
    record Frame(String className, String method, int line) {
    }

    static Frame parseFrame(String frame) {
        int open = frame.length() - 1;                          // the "(" that the final ")" closes
        for (int depth = 0; open > 0; open--) {
            depth += frame.charAt(open) == ')' ? 1 : frame.charAt(open) == '(' ? -1 : 0;
            if (depth == 0) {
                break;
            }
        }
        if (open <= 0) {
            return null;
        }
        String name = frame.substring(0, open).substring(frame.substring(0, open).lastIndexOf('/') + 1); // without "app//"
        String location = frame.substring(open + 1, frame.length() - 1);
        String line = location.substring(location.lastIndexOf(':') + 1);
        int dot = name.lastIndexOf('.');
        return new Frame(dot < 0 ? "" : name.substring(0, dot), name.substring(dot + 1),
                line.matches("\\d+") ? Integer.parseInt(line) : -1);
    }

    /** The last frame in this part of a trace, or null. */
    static Frame lastFrame(String trace) {
        Frame last = null;
        Matcher m = FRAME.matcher(trace);
        while (m.find()) {
            Frame parsed = parseFrame(m.group(1));
            if (parsed != null) {
                last = parsed;
            }
        }
        return last;
    }

    /**
     * The compiled glue the mutant run loaded: its classpath (class directories and jars, in its order), searched the
     * way its class loader searches it. Cucumber cuts a step's stack right behind the glue method it called and knows
     * that method by the binary name of its class and its name (AbstractGlueDefinition.isDefinedAt). The verdict
     * looks up exactly that class and reads whether that method carries one of Cucumber's transformer annotations -
     * whatever the language of the glue (a Kotlin top-level function is a method of its FileKt class, a nested class
     * is Outer$Inner) and whatever the encoding of its sources.
     */
    static final class Glue {
        final List<String> classpath;
        /** class -> the names of its transformer methods -> their lines */
        final Map<String, Map<String, Lines>> classes = new HashMap<>();

        Glue(List<String> classpath) {
            this.classpath = classpath;
        }

        /**
         * Whether the frame is a transformer method's: the same class and method name. A step definition can overload
         * a transformer's name in the same class; its frame is told apart only by positive evidence - a line of the
         * same-named method without a transformer annotation that is no transformer's line. Without that evidence
         * (no line numbers, a line no table names) the frame is the transformer's, and the failure is no kill.
         */
        boolean isTransformer(Frame frame) throws IOException {
            if (frame == null) {
                return false;
            }
            Map<String, Lines> methods = classes.get(frame.className());
            if (methods == null) {
                methods = transformersOf(frame.className());
                classes.put(frame.className(), methods);
            }
            Lines lines = methods.get(frame.method());
            return lines != null && !(lines.other().contains(frame.line()) && !lines.transformer().contains(frame.line()));
        }

        /** Worker-thread trace without "✽.": checks every frame on the glue classpath (including synthetic lambda$
         *  frames inside a transformer). Throws when non-JDK frames exist and none of them are on the glue classpath. */
        boolean hasTransformerFrame(String trace) throws IOException {
            Matcher m = FRAME.matcher(trace);
            boolean sawNonJdk = false;
            boolean sawClasspath = classpath.isEmpty();
            while (m.find()) {
                Frame frame = parseFrame(m.group(1));
                if (frame == null || frame.className().isEmpty()) {
                    continue;
                }
                String resource = frame.className().replace('.', '/') + ".class";
                if (ClassLoader.getPlatformClassLoader().getResource(resource) != null) {
                    continue;
                }
                sawNonJdk = true;
                Map<String, Lines> methods = classes.get(frame.className());
                if (methods == null) {
                    methods = findTransformersOf(frame.className());
                    if (methods == null) {
                        continue;
                    }
                    classes.put(frame.className(), methods);
                }
                sawClasspath = true;
                Lines lines = methods.get(frame.method());
                if (lines != null && !(lines.other().contains(frame.line()) && !lines.transformer().contains(frame.line()))) {
                    return true;
                }
                Matcher syn = LAMBDA_METHOD.matcher(frame.method());
                if (syn.matches() && methods.containsKey(syn.group(1))) {
                    return true;
                }
            }
            if (sawNonJdk && !sawClasspath) {
                throw new IOException("no class of a worker-thread step failure trace is on the glue classpath (--glue)"
                        + " - pass the classpath of the mutant run");
            }
            return false;
        }

        Map<String, Lines> findTransformersOf(String className) throws IOException {
            String resource = className.replace('.', '/') + ".class";
            for (String entry : classpath) {
                Path root = Path.of(entry);
                if (Files.isDirectory(root)) {
                    Path file = root.resolve(resource);
                    if (Files.isRegularFile(file)) {
                        return transformerMethods(Files.readAllBytes(file), file.toString());
                    }
                } else if (Files.isRegularFile(root)) {
                    ZipFile jar;
                    try {
                        jar = new ZipFile(root.toFile());
                    } catch (ZipException notAJar) {
                        continue;                                       // a class loader skips it as well
                    }
                    try (jar) {
                        ZipEntry e = jar.getEntry(resource);
                        if (e != null) {
                            try (InputStream in = jar.getInputStream(e)) {
                                return transformerMethods(in.readAllBytes(), root + "!/" + resource);
                            }
                        }
                    }
                }
            }
            return null;
        }

        /** The transformer methods of the first class file of that name on the classpath. A class that is not there
         *  fails the verdict: whether its method was a transformer is unknown, and a guess could count a rejected
         *  value as a kill. */
        Map<String, Lines> transformersOf(String className) throws IOException {
            Map<String, Lines> found = findTransformersOf(className);
            if (found != null) {
                return found;
            }
            String resource = className.replace('.', '/') + ".class";
            if (ClassLoader.getPlatformClassLoader().getResource(resource) != null) {
                return Map.of();                                // a class of the JDK itself is never glue
            }
            throw new IOException("class " + className + " of a step frame is not on the glue classpath (--glue), so"
                    + " a transformer cannot be told from a step definition - pass the classpath of the mutant run");
        }
    }

    /** Cucumber's transformer annotations, as a class file names their types. */
    static final Set<String> TRANSFORMER_ANNOTATIONS = Set.of("Lio/cucumber/java/ParameterType;",
            "Lio/cucumber/java/DataTableType;", "Lio/cucumber/java/DocStringType;",
            "Lio/cucumber/java/DefaultParameterTransformer;", "Lio/cucumber/java/DefaultDataTableEntryTransformer;",
            "Lio/cucumber/java/DefaultDataTableCellTransformer;");

    /** The source lines of the methods with one name: of those with a transformer annotation, of the others. */
    record Lines(Set<Integer> transformer, Set<Integer> other) {
    }

    /** The names of the methods of one class file (JVM specification, chapter 4) that carry a transformer annotation,
     *  with the lines of every method of that name (empty without line numbers). */
    static Map<String, Lines> transformerMethods(byte[] bytes, String where) throws IOException {
        Map<String, Set<Integer>> transformers = new HashMap<>();
        Map<String, Set<Integer>> others = new HashMap<>();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            skip(in, 4);                                                // minor and major version
            String[] utf8 = new String[in.readUnsignedShort()];
            for (int i = 1; i < utf8.length; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();                   // modified UTF-8, the format of DataInput
                    case 7, 8, 16, 19, 20 -> skip(in, 2);
                    case 15 -> skip(in, 3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> skip(in, 4);
                    case 5, 6 -> {
                        skip(in, 8);
                        i++;                                            // a long or a double takes two entries
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            skip(in, 6);                                                // access flags, this class, super class
            skip(in, 2 * in.readUnsignedShort());                       // interfaces
            for (int fields = in.readUnsignedShort(); fields > 0; fields--) {
                skip(in, 6);
                for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
                    skip(in, 2);
                    skip(in, in.readInt());
                }
            }
            for (int methods = in.readUnsignedShort(); methods > 0; methods--) {
                skip(in, 2);                                            // access flags
                String method = utf8[in.readUnsignedShort()];
                skip(in, 2);                                            // descriptor
                boolean transformer = false;
                Set<Integer> lines = new HashSet<>();
                for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
                    String attribute = utf8[in.readUnsignedShort()];
                    DataInputStream a = new DataInputStream(new ByteArrayInputStream(bytes(in, in.readInt())));
                    if ("RuntimeVisibleAnnotations".equals(attribute)) {
                        for (int n = a.readUnsignedShort(); n > 0; n--) {
                            transformer |= TRANSFORMER_ANNOTATIONS.contains(utf8[a.readUnsignedShort()]);
                            skipElementValuePairs(a);
                        }
                    } else if ("Code".equals(attribute)) {
                        skip(a, 4);                                     // max stack, max locals
                        skip(a, a.readInt());                           // the bytecode
                        skip(a, 8 * a.readUnsignedShort());             // the exception table
                        for (int n = a.readUnsignedShort(); n > 0; n--) {
                            String codeAttribute = utf8[a.readUnsignedShort()];
                            int length = a.readInt();
                            if (!"LineNumberTable".equals(codeAttribute)) {
                                skip(a, length);
                                continue;
                            }
                            for (int entries = a.readUnsignedShort(); entries > 0; entries--) {
                                skip(a, 2);                             // start pc
                                lines.add(a.readUnsignedShort());
                            }
                        }
                    }
                }
                (transformer ? transformers : others).computeIfAbsent(method, k -> new HashSet<>()).addAll(lines);
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("cannot read the glue class " + where + ": " + e, e);
        }
        Map<String, Lines> found = new HashMap<>();
        transformers.forEach((method, lines) -> found.put(method, new Lines(lines, others.getOrDefault(method, Set.of()))));
        return found;
    }

    /** Skips the element-value pairs of an annotation whose type index was just read. */
    static void skipElementValuePairs(DataInputStream a) throws IOException {
        for (int pairs = a.readUnsignedShort(); pairs > 0; pairs--) {
            skip(a, 2);                                                 // the element's name
            skipElementValue(a);
        }
    }

    static void skipElementValue(DataInputStream a) throws IOException {
        int tag = a.readUnsignedByte();
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> skip(a, 2);
            case 'e' -> skip(a, 4);                                     // an enum constant: its type and name
            case '@' -> {                                               // a nested annotation
                skip(a, 2);
                skipElementValuePairs(a);
            }
            case '[' -> {
                for (int n = a.readUnsignedShort(); n > 0; n--) {
                    skipElementValue(a);
                }
            }
            default -> throw new IOException("unknown annotation element tag " + tag);
        }
    }

    /** Skips exactly n bytes of a class file: a length beyond its end is a broken class file, not a shorter one. */
    static void skip(DataInputStream in, int n) throws IOException {
        if (n < 0 || n > in.available()) {
            throw new IOException("truncated class file");
        }
        in.skipNBytes(n);
    }

    static byte[] bytes(DataInputStream in, int n) throws IOException {
        if (n < 0 || n > in.available()) {
            throw new IOException("truncated class file");
        }
        return in.readNBytes(n);
    }

    /** The manifest's line of the first step that uses the mutated column; 0 when no step uses it (generate writes 0
     *  then), -1 when the manifest does not say (an older one): a failure is then a kill, as it was. Reading both as 0
     *  counted any failure of a mutant of an unused column as a kill (review of 7061885). */
    static int firstUseLine(String[] manifestRow) {
        return manifestRow.length > 7 && manifestRow[7].matches("\\d+") ? Integer.parseInt(manifestRow[7]) : -1;
    }

    /** Cucumber reports a step without a matching definition with this specific exception. */
    static boolean undefinedStep(Element failure) {
        String text = failure.getAttribute("type") + " " + trace(failure);
        return text.contains("UndefinedStepException");
    }

    /** Only for the summary note: kills by an exception that is not one of the usual assertion classes are
     *  counted separately so the human can see them. It never decides between kill and error. */
    static boolean assertionType(String type) {
        return type.endsWith("AssertionError") || type.endsWith("AssertionFailedError")
                || type.endsWith("AssertFailedError") || type.endsWith("ComparisonFailure")
                || type.endsWith("ComparisonFailureWithFacts") || type.endsWith("AssertionErrorWithFacts")
                || type.endsWith("MultipleFailuresError");
    }

    // ---------------------------------------------------------------- helpers

    static boolean matchesAny(Path file, List<String> only) {
        String p = file.toString().replace('\\', '/');
        for (String o : only) {
            String n = o.replace('\\', '/');
            if (p.equals(n) || p.endsWith("/" + n) || n.endsWith("/" + p) || n.endsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The glue classpath of the verdict: every --glue entry, then the entries of --glue-file; null = none given
     *  (transformers are then not told apart, as without a classpath at all). */
    static Glue glue(List<String> entries, String listFile) throws IOException {
        if (entries.isEmpty() && listFile == null) {
            return null;
        }
        List<String> classpath = new ArrayList<>(entries);
        if (listFile != null) {
            classpath.addAll(pathList(Path.of(listFile)));
        }
        return new Glue(classpath);
    }

    /** The paths of a list file, each ended by a NUL character ({@code git diff --name-only -z}), exactly as written. */
    static List<String> pathList(Path file) throws IOException {
        List<String> list = new ArrayList<>();
        for (String path : Files.readString(file, StandardCharsets.UTF_8).split("\0")) {
            if (!path.isEmpty()) {
                list.add(path);
            }
        }
        return list;
    }

    /** One field of a line-based file: a tab, a line feed or a carriage return (in a file name too) ends nothing. */
    static String tsv(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    // ---------------------------------------------------------------- glue impact (method-level bytecode scope)

    /**
     * Narrows {@code generate} scope when acceptance glue {@code .java} files changed: instead of mutating every
     * feature whenever any glue file is touched, compares compiled {@code base} and {@code head} glue bytecode at
     * method and field granularity and joins changed glue definitions against {@code acceptanceTest}'s Cucumber
     * message stream ({@code stepDefinition}, {@code hook}, {@code parameterType} -> {@code testCase} ->
     * {@code pickle.uri}). Any global context/hook/transformer change or unreadable input conservatively returns
     * {@code null} (mutate every feature).
     */
    static final class GlueImpact {
        static final Set<String> GLOBAL_CLASS_ANNOTATIONS = Set.of(
                "Lio/cucumber/spring/CucumberContextConfiguration;",
                "Lorg/springframework/context/annotation/Configuration;",
                "Lorg/springframework/boot/test/context/TestConfiguration;",
                "Lorg/springframework/boot/test/context/SpringBootTest;",
                "Lorg/springframework/test/context/ContextConfiguration;",
                "Lorg/springframework/context/annotation/Primary;",
                "Lorg/aspectj/lang/annotation/Aspect;");
        static final Set<String> GLOBAL_INTERFACES = Set.of(
                "org/springframework/beans/factory/config/BeanPostProcessor",
                "org/springframework/beans/factory/config/BeanFactoryPostProcessor",
                "org/springframework/context/ApplicationListener",
                "org/springframework/context/SmartLifecycle",
                "org/springframework/beans/factory/InitializingBean",
                "org/springframework/beans/factory/DisposableBean");
        static final Set<String> GLOBAL_METHOD_ANNOTATIONS = Set.of(
                "Lorg/springframework/context/annotation/Bean;",
                "Lorg/springframework/context/event/EventListener;",
                "Lorg/springframework/amqp/rabbit/annotation/RabbitListener;",
                "Lorg/springframework/scheduling/annotation/Scheduled;",
                "Ljakarta/annotation/PostConstruct;",
                "Ljakarta/annotation/PreDestroy;",
                "Ljavax/annotation/PostConstruct;",
                "Ljavax/annotation/PreDestroy;",
                "Lio/cucumber/java/BeforeAll;",
                "Lio/cucumber/java/AfterAll;",
                "Lio/cucumber/java/DocStringType;",
                "Lio/cucumber/java/DefaultParameterTransformer;",
                "Lio/cucumber/java/DefaultDataTableEntryTransformer;",
                "Lio/cucumber/java/DefaultDataTableCellTransformer;");
        static final Set<String> SCENARIO_HOOK_ANNOTATIONS = Set.of(
                "Lio/cucumber/java/Before;",
                "Lio/cucumber/java/After;",
                "Lio/cucumber/java/BeforeStep;",
                "Lio/cucumber/java/AfterStep;");
        static final String PARAMETER_TYPE = "Lio/cucumber/java/ParameterType;";
        static final String DATA_TABLE_TYPE = "Lio/cucumber/java/DataTableType;";
        static final int RELEVANT_METHOD_FLAGS = 0x0008 | 0x0020 | 0x0100 | 0x0400 | 0x0800;
        static final int RELEVANT_FIELD_FLAGS = 0x0008 | 0x0010 | 0x0040;

        record MethodKey(String owner, String name, String desc) implements Comparable<MethodKey> {
            @Override
            public int compareTo(MethodKey o) {
                int c = owner.compareTo(o.owner);
                if (c != 0) {
                    return c;
                }
                c = name.compareTo(o.name);
                return c != 0 ? c : desc.compareTo(o.desc);
            }
        }

        record FieldKey(String owner, String name, String desc) implements Comparable<FieldKey> {
            @Override
            public int compareTo(FieldKey o) {
                int c = owner.compareTo(o.owner);
                if (c != 0) {
                    return c;
                }
                c = name.compareTo(o.name);
                return c != 0 ? c : desc.compareTo(o.desc);
            }
        }

        record JavaTarget(String owner, String methodName, String paramDescPrefix) {
        }

        record RawMember(int flags, String name, String desc, List<AttrBytes> attrs) {
        }

        record AttrBytes(String name, byte[] data) {
        }

        record CpClass(int nameIdx) {
        }

        record CpString(int utf8Idx) {
        }

        record CpRef(int tag, int classIdx, int natIdx) {
        }

        record CpNat(int nameIdx, int descIdx) {
        }

        record CpHandle(int kind, int refIdx) {
        }

        record CpMethodType(int descIdx) {
        }

        record CpIndy(int tag, int bsmIdx, int natIdx) {
        }

        record Bsm(int mhIdx, List<Integer> args) {
        }

        record CodeDigest(String hash, List<MethodKey> calls, List<FieldKey> fields, Set<String> types) {
        }

        record FieldInfo(int flags, String name, String desc, Map<String, String> annotations, String ownHash,
                         List<MethodKey> calls, List<FieldKey> fields, Set<String> types) {
        }

        record MethodInfo(int flags, String name, String desc, Map<String, String> annotations, String ownHash,
                          List<MethodKey> calls, List<FieldKey> fields, Set<String> types) {
            boolean isStatic() {
                return (flags & 0x0008) != 0;
            }
        }

        static final class ClassInfo {
            final String name;
            final String superName;
            final List<String> interfaces;
            final Map<String, String> annotations;
            final String headerHash;
            final String wholeClassHash;
            final boolean nonPureInits;
            final boolean hasSharedClinit;
            final Map<String, FieldInfo> fields;
            final Map<String, MethodInfo> methods;

            ClassInfo(String name, String superName, List<String> interfaces, Map<String, String> annotations,
                      String headerHash, String wholeClassHash, boolean nonPureInits, boolean hasSharedClinit,
                      Map<String, FieldInfo> fields, Map<String, MethodInfo> methods) {
                this.name = name;
                this.superName = superName;
                this.interfaces = interfaces;
                this.annotations = annotations;
                this.headerHash = headerHash;
                this.wholeClassHash = wholeClassHash;
                this.nonPureInits = nonPureInits;
                this.hasSharedClinit = hasSharedClinit;
                this.fields = fields;
                this.methods = methods;
            }

            boolean isGlobalClass() {
                for (String ann : annotations.keySet()) {
                    if (GLOBAL_CLASS_ANNOTATIONS.contains(ann)) {
                        return true;
                    }
                }
                for (String itf : interfaces) {
                    if (GLOBAL_INTERFACES.contains(itf) || itf.startsWith("io/cucumber/plugin/")) {
                        return true;
                    }
                }
                return false;
            }
        }

        static List<String> narrow(List<Path> featureFiles, List<String> changedFeatures, List<String> spec,
                                   PrintStream out) {
            try {
                return narrowChecked(featureFiles, changedFeatures, spec, out);
            } catch (IOException | RuntimeException e) {
                out.println("Gherkin mutation: glue impact fallback (" + e.getMessage() + ") - every feature is mutated");
                return null;
            }
        }

        static List<String> narrowChecked(List<Path> featureFiles, List<String> changedFeatures, List<String> spec,
                                          PrintStream out) throws IOException {
            if (spec.size() < 3) {
                return fallback(out, "incomplete --glue-impact specification");
            }
            Path messagesPath = Path.of(spec.get(0));
            List<Path> baseDirs = new ArrayList<>();
            List<Path> headDirs = new ArrayList<>();
            List<Path> current = null;
            for (int i = 1; i < spec.size(); i++) {
                String token = spec.get(i);
                if ("--base".equals(token)) {
                    current = baseDirs;
                } else if ("--head".equals(token)) {
                    current = headDirs;
                } else if (current != null) {
                    current.add(Path.of(token));
                }
            }
            GlueGraph base = GlueGraph.load(baseDirs);
            GlueGraph head = GlueGraph.load(headDirs);
            String globalReason = globalChangeReason(base, head);
            if (globalReason != null) {
                return fallback(out, globalReason);
            }
            List<Path> streamFiles = listStreams(messagesPath);
            if (streamFiles.isEmpty()) {
                return fallback(out, "no Cucumber acceptance message stream");
            }
            Map<String, JavaTarget> stepDefs = new HashMap<>();
            Map<String, JavaTarget> hooks = new HashMap<>();
            Map<String, JavaTarget> paramTypes = new HashMap<>();
            Set<String> runHookIds = new HashSet<>();
            Map<String, Path> pickleFiles = new HashMap<>();
            Set<Path> compiledFiles = new HashSet<>();
            List<Map<String, Object>> testCases = new ArrayList<>();
            for (Path sf : streamFiles) {
                readAcceptanceStream(sf, stepDefs, hooks, paramTypes, runHookIds, pickleFiles,
                        compiledFiles, testCases);
            }
            for (String hookId : runHookIds) {
                JavaTarget target = hooks.get(hookId);
                if (target == null || targetChanged(base, head, target)) {
                    return fallback(out, "changed run-level hook");
                }
            }
            Set<String> changedStepIds = new HashSet<>();
            for (Map.Entry<String, JavaTarget> e : stepDefs.entrySet()) {
                if (e.getValue() == null || targetChanged(base, head, e.getValue())) {
                    changedStepIds.add(e.getKey());
                }
            }
            Set<String> changedHookIds = new HashSet<>();
            for (Map.Entry<String, JavaTarget> e : hooks.entrySet()) {
                if (e.getValue() == null || targetChanged(base, head, e.getValue())) {
                    changedHookIds.add(e.getKey());
                }
            }
            Set<String> changedParamNames = new HashSet<>();
            for (Map.Entry<String, JavaTarget> e : paramTypes.entrySet()) {
                if (e.getValue() == null || targetChanged(base, head, e.getValue())) {
                    changedParamNames.add(e.getKey());
                }
            }
            Set<Path> impactedRealPaths = new HashSet<>();
            for (Map<String, Object> tc : testCases) {
                Path feature = pickleFiles.get(Json.text(tc, "pickleId"));
                if (feature == null || impactedRealPaths.contains(feature)) {
                    continue;
                }
                if (testCaseImpacted(tc, hooks.keySet(), changedStepIds, changedHookIds, changedParamNames)) {
                    impactedRealPaths.add(feature);
                }
            }
            List<String> scoped = new ArrayList<>();
            for (Path file : featureFiles) {
                boolean directlyChanged = changedFeatures != null && matchesAny(file, changedFeatures);
                Path real = realPath(file);
                boolean unloaded = hasGherkin(file) && !compiledFiles.contains(real);
                if (directlyChanged || impactedRealPaths.contains(real) || unloaded) {
                    scoped.add(file.toString());
                }
            }
            out.println("Gherkin mutation: glue impact narrowed scope to " + scoped.size() + " of "
                    + featureFiles.size() + " feature file(s)");
            return scoped;
        }

        static List<String> fallback(PrintStream out, String reason) {
            out.println("Gherkin mutation: glue impact affects global glue (" + reason + ") - every feature is mutated");
            return null;
        }

        static String globalChangeReason(GlueGraph base, GlueGraph head) {
            Set<String> allClasses = new TreeSet<>(base.classes.keySet());
            allClasses.addAll(head.classes.keySet());
            for (String cls : allClasses) {
                ClassInfo b = base.classes.get(cls);
                ClassInfo h = head.classes.get(cls);
                if ((b != null && b.isGlobalClass()) || (h != null && h.isGlobalClass())) {
                    if (b == null || h == null || !b.wholeClassHash.equals(h.wholeClassHash)) {
                        return "context configuration " + cls;
                    }
                }
            }
            Set<MethodKey> globalMethods = new TreeSet<>();
            collectAnnotatedMethods(base, GLOBAL_METHOD_ANNOTATIONS, globalMethods);
            collectAnnotatedMethods(head, GLOBAL_METHOD_ANNOTATIONS, globalMethods);
            for (MethodKey key : globalMethods) {
                String bSig = base.globalBeanSignature(key);
                String hSig = head.globalBeanSignature(key);
                if (!Objects.equals(bSig, hSig)) {
                    return "global method " + key.owner + "#" + key.name;
                }
            }
            for (Map.Entry<String, ClassInfo> ce : base.classes.entrySet()) {
                ClassInfo hClass = head.classes.get(ce.getKey());
                for (MethodInfo bm : ce.getValue().methods.values()) {
                    MethodKey key = new MethodKey(ce.getKey(), bm.name, bm.desc);
                    MethodInfo hm = hClass == null ? null : hClass.methods.get(bm.name + bm.desc);
                    if (hasAnyAnnotation(bm.annotations, SCENARIO_HOOK_ANNOTATIONS)) {
                        if (hm == null || !sameSubsetAnnotations(bm.annotations, hm.annotations, SCENARIO_HOOK_ANNOTATIONS)) {
                            return "removed or re-scoped hook " + key.owner + "#" + key.name;
                        }
                    }
                    if (bm.annotations.containsKey(PARAMETER_TYPE)) {
                        if (hm == null || !Objects.equals(bm.annotations.get(PARAMETER_TYPE),
                                hm.annotations.get(PARAMETER_TYPE))) {
                            return "removed or re-scoped @ParameterType " + key.owner + "#" + key.name;
                        }
                    }
                    if (bm.annotations.containsKey(DATA_TABLE_TYPE)) {
                        if (hm == null || !Objects.equals(base.methodSignature(key), head.methodSignature(key))) {
                            return "changed @DataTableType " + key.owner + "#" + key.name;
                        }
                    }
                    if (hasStepAnnotation(bm.annotations) && (hm == null || !hasStepAnnotation(hm.annotations))) {
                        return "removed step definition " + key.owner + "#" + key.name;
                    }
                }
            }
            for (Map.Entry<String, ClassInfo> ce : head.classes.entrySet()) {
                ClassInfo bClass = base.classes.get(ce.getKey());
                for (MethodInfo hm : ce.getValue().methods.values()) {
                    MethodInfo bm = bClass == null ? null : bClass.methods.get(hm.name + hm.desc);
                    if (bm == null && hm.annotations.containsKey(PARAMETER_TYPE)
                            && hm.annotations.get(PARAMETER_TYPE).contains("preferForRegexpMatch=true")) {
                        return "preferForRegexpMatch @ParameterType " + ce.getKey() + "#" + hm.name;
                    }
                    if (bm == null && hm.annotations.containsKey(DATA_TABLE_TYPE)) {
                        String ret = returnInternalName(hm.desc);
                        if (ret == null || !head.classes.containsKey(ret) || base.classes.containsKey(ret)) {
                            return "added @DataTableType for existing type " + ce.getKey() + "#" + hm.name;
                        }
                    }
                }
            }
            return null;
        }

        static void collectAnnotatedMethods(GlueGraph g, Set<String> targets, Set<MethodKey> out) {
            for (ClassInfo c : g.classes.values()) {
                for (MethodInfo m : c.methods.values()) {
                    if (hasAnyAnnotation(m.annotations, targets)) {
                        out.add(new MethodKey(c.name, m.name, m.desc));
                    }
                }
            }
        }

        static boolean hasAnyAnnotation(Map<String, String> anns, Set<String> targets) {
            for (String k : anns.keySet()) {
                if (targets.contains(k)) {
                    return true;
                }
            }
            return false;
        }

        static boolean sameSubsetAnnotations(Map<String, String> a, Map<String, String> b, Set<String> subset) {
            for (String k : subset) {
                if (!Objects.equals(a.get(k), b.get(k))) {
                    return false;
                }
            }
            return true;
        }

        static boolean hasStepAnnotation(Map<String, String> anns) {
            for (String k : anns.keySet()) {
                if (k.startsWith("Lio/cucumber/java/") && k.indexOf('/', "Lio/cucumber/java/".length()) > 0) {
                    return true;
                }
            }
            return false;
        }

        static String returnInternalName(String methodDesc) {
            int close = methodDesc.lastIndexOf(')');
            if (close < 0 || close + 2 >= methodDesc.length()) {
                return null;
            }
            String ret = methodDesc.substring(close + 1);
            return ret.startsWith("L") && ret.endsWith(";") ? ret.substring(1, ret.length() - 1) : null;
        }

        static boolean targetChanged(GlueGraph base, GlueGraph head, JavaTarget target) {
            String bSig = base.targetSignature(target);
            String hSig = head.targetSignature(target);
            return bSig == null || hSig == null || !bSig.equals(hSig);
        }

        static boolean testCaseImpacted(Map<String, Object> tc, Set<String> knownHookIds, Set<String> changedStepIds,
                                        Set<String> changedHookIds, Set<String> changedParamNames) {
            if (!(tc.get("testSteps") instanceof List<?> steps)) {
                return false;
            }
            for (Object stepObj : steps) {
                Map<String, Object> ts = Json.object(stepObj, "testCase.testSteps");
                if (ts.get("hookId") instanceof String hookId && !hookId.isEmpty()) {
                    if (!knownHookIds.contains(hookId) || changedHookIds.contains(hookId)) {
                        return true;
                    }
                    continue;
                }
                if (!(ts.get("stepDefinitionIds") instanceof List<?> sdIds) || sdIds.size() != 1) {
                    return true;
                }
                if (!(sdIds.get(0) instanceof String sdId) || changedStepIds.contains(sdId)) {
                    return true;
                }
                if (ts.get("stepMatchArgumentsLists") instanceof List<?> argLists) {
                    for (Object listObj : argLists) {
                        Map<String, Object> listMap = Json.object(listObj, "stepMatchArgumentsLists");
                        if (listMap.get("stepMatchArguments") instanceof List<?> args) {
                            for (Object argObj : args) {
                                Map<String, Object> arg = Json.object(argObj, "stepMatchArguments");
                                if (arg.get("parameterTypeName") instanceof String pName
                                        && changedParamNames.contains(pName)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        static List<Path> listStreams(Path messages) throws IOException {
            if (Files.isDirectory(messages)) {
                try (Stream<Path> s = Files.list(messages)) {
                    return s.filter(p -> p.toString().endsWith(".ndjson") && Files.isRegularFile(p)).sorted().toList();
                }
            }
            return Files.isRegularFile(messages) ? List.of(messages) : List.of();
        }

        static void readAcceptanceStream(Path file, Map<String, JavaTarget> stepDefs, Map<String, JavaTarget> hooks,
                                         Map<String, JavaTarget> paramTypes,
                                         Set<String> runHookIds, Map<String, Path> pickleFiles,
                                         Set<Path> compiledFiles, List<Map<String, Object>> testCases)
                throws IOException {
            int envelopes = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> envelope = Json.object(Json.parse(line), "envelope");
                envelopes++;
                for (Map.Entry<String, Object> e : envelope.entrySet()) {
                    switch (e.getKey()) {
                        case "stepDefinition" -> {
                            Map<String, Object> sd = Json.object(e.getValue(), "stepDefinition");
                            stepDefs.put(Json.text(sd, "id"), parseSourceRef(sd.get("sourceReference")));
                        }
                        case "hook" -> {
                            Map<String, Object> hook = Json.object(e.getValue(), "hook");
                            hooks.put(Json.text(hook, "id"), parseSourceRef(hook.get("sourceReference")));
                        }
                        case "parameterType" -> {
                            Map<String, Object> pt = Json.object(e.getValue(), "parameterType");
                            paramTypes.put(Json.text(pt, "name"), parseSourceRef(pt.get("sourceReference")));
                        }
                        case "testRunHookStarted" -> {
                            Map<String, Object> trh = Json.object(e.getValue(), "testRunHookStarted");
                            runHookIds.add(Json.text(trh, "hookId"));
                        }
                        case "pickle" -> {
                            Map<String, Object> pickle = Json.object(e.getValue(), "pickle");
                            Path f = featureFile(Json.text(pickle, "uri"));
                            if (f != null) {
                                pickleFiles.put(Json.text(pickle, "id"), f);
                                compiledFiles.add(f);
                            }
                        }
                        case "testCase" -> testCases.add(Json.object(e.getValue(), "testCase"));
                        default -> {
                        }
                    }
                }
            }
            if (envelopes == 0) {
                throw new IOException("empty acceptance message stream " + file);
            }
        }

        static JavaTarget parseSourceRef(Object refObj) {
            if (!(refObj instanceof Map<?, ?> map)) {
                return null;
            }
            if (map.get("javaMethod") instanceof Map<?, ?> jmObj) {
                Map<String, Object> jm = Json.object(jmObj, "javaMethod");
                String owner = Json.text(jm, "className").replace('.', '/');
                String method = Json.text(jm, "methodName");
                StringBuilder sb = new StringBuilder("(");
                for (Object p : Json.list(jm.get("methodParameterTypes"), "methodParameterTypes")) {
                    sb.append(typeToDescriptor(Json.string(p, "methodParameterTypes")));
                }
                sb.append(")");
                return new JavaTarget(owner, method, sb.toString());
            }
            if (map.get("javaStackTraceElement") instanceof Map<?, ?> steObj) {
                Map<String, Object> ste = Json.object(steObj, "javaStackTraceElement");
                return new JavaTarget(Json.text(ste, "className").replace('.', '/'),
                        Json.text(ste, "methodName"), null);
            }
            return null;
        }

        static String typeToDescriptor(String typeName) {
            return switch (typeName) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                case "void" -> "V";
                default -> {
                    if (typeName.startsWith("[")) {
                        yield typeName.replace('.', '/');
                    }
                    if (typeName.endsWith("[]")) {
                        yield "[" + typeToDescriptor(typeName.substring(0, typeName.length() - 2));
                    }
                    yield "L" + typeName.replace('.', '/') + ";";
                }
            };
        }

        static final class GlueGraph {
            final Map<String, ClassInfo> classes;
            final Map<String, Set<String>> directSubtypes = new HashMap<>();
            final Map<MethodKey, String> sigCache = new HashMap<>();

            GlueGraph(Map<String, ClassInfo> classes) {
                this.classes = classes;
                for (ClassInfo c : classes.values()) {
                    if (c.superName != null) {
                        directSubtypes.computeIfAbsent(c.superName, k -> new TreeSet<>()).add(c.name);
                    }
                    for (String itf : c.interfaces) {
                        directSubtypes.computeIfAbsent(itf, k -> new TreeSet<>()).add(c.name);
                    }
                }
            }

            static GlueGraph load(List<Path> dirs) throws IOException {
                Map<String, ClassInfo> map = new LinkedHashMap<>();
                for (Path dir : dirs) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    List<Path> classFiles;
                    try (Stream<Path> s = Files.walk(dir)) {
                        classFiles = s.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p))
                                .sorted().toList();
                    }
                    for (Path cf : classFiles) {
                        ClassInfo info = parseClass(Files.readAllBytes(cf), cf.toString());
                        map.putIfAbsent(info.name, info);
                    }
                }
                return new GlueGraph(map);
            }

            String targetSignature(JavaTarget target) {
                ClassInfo c = classes.get(target.owner);
                if (c == null) {
                    return null;
                }
                List<MethodKey> matched = new ArrayList<>();
                for (MethodInfo m : c.methods.values()) {
                    if (m.name.equals(target.methodName)
                            && (target.paramDescPrefix == null || m.desc.startsWith(target.paramDescPrefix))) {
                        matched.add(new MethodKey(c.name, m.name, m.desc));
                    }
                }
                if (matched.isEmpty()) {
                    return null;
                }
                matched.sort(MethodKey::compareTo);
                MessageDigest md = sha256();
                for (MethodKey key : matched) {
                    update(md, key.desc + "=" + methodSignature(key) + ";");
                }
                return hex(md);
            }

            String globalBeanSignature(MethodKey root) {
                ClassInfo c = classes.get(root.owner);
                if (c == null || !c.methods.containsKey(root.name + root.desc)) {
                    return null;
                }
                MessageDigest md = sha256();
                update(md, methodSignature(root) + "|");
                Set<String> visitedTypes = new TreeSet<>();
                Deque<String> typeQueue = new ArrayDeque<>();
                MethodInfo rm = c.methods.get(root.name + root.desc);
                typeQueue.addAll(rm.types);
                for (MethodKey call : rm.calls) {
                    typeQueue.add(call.owner);
                }
                while (!typeQueue.isEmpty()) {
                    String t = typeQueue.removeFirst();
                    if (!classes.containsKey(t) || !visitedTypes.add(t)) {
                        continue;
                    }
                    ClassInfo tc = classes.get(t);
                    if (tc.superName != null) {
                        typeQueue.add(tc.superName);
                    }
                    typeQueue.addAll(tc.interfaces);
                    for (MethodInfo m : tc.methods.values()) {
                        typeQueue.addAll(m.types);
                        for (MethodKey call : m.calls) {
                            typeQueue.add(call.owner);
                        }
                    }
                }
                for (String t : visitedTypes) {
                    update(md, t + "=" + classes.get(t).wholeClassHash + ";");
                }
                return hex(md);
            }

            String methodSignature(MethodKey root) {
                String cached = sigCache.get(root);
                if (cached != null) {
                    return cached;
                }
                ClassInfo rootClass = classes.get(root.owner);
                if (rootClass == null || !rootClass.methods.containsKey(root.name + root.desc)) {
                    return null;
                }
                MessageDigest md = sha256();
                Set<MethodKey> seenMethods = new HashSet<>();
                Set<FieldKey> seenFields = new HashSet<>();
                Set<String> seenTypes = new TreeSet<>();
                Deque<MethodKey> mQueue = new ArrayDeque<>();
                Deque<FieldKey> fQueue = new ArrayDeque<>();
                int lambdaSeq = 0;
                mQueue.add(root);
                while (!mQueue.isEmpty() || !fQueue.isEmpty()) {
                    while (!mQueue.isEmpty()) {
                        MethodKey mk = mQueue.removeFirst();
                        for (MethodKey resolved : resolveMethods(mk)) {
                            if (!seenMethods.add(resolved)) {
                                continue;
                            }
                            ClassInfo ci = classes.get(resolved.owner);
                            MethodInfo mi = ci.methods.get(resolved.name + resolved.desc);
                            seenTypes.add(ci.name);
                            addSupertypes(ci.name, seenTypes);
                            if (LAMBDA_METHOD.matcher(mi.name).matches()) {
                                update(md, "L#" + (lambdaSeq++) + ":" + ci.name + mi.desc + "=" + mi.ownHash + "\n");
                            } else {
                                update(md, "M:" + ci.name + "." + mi.name + mi.desc + "=" + mi.ownHash + "\n");
                            }
                            if (!mi.isStatic() && ci.nonPureInits) {
                                for (MethodInfo init : ci.methods.values()) {
                                    if ("<init>".equals(init.name)) {
                                        mQueue.add(new MethodKey(ci.name, init.name, init.desc));
                                    }
                                }
                            }
                            if (ci.hasSharedClinit && ci.methods.containsKey("<clinit>()V")) {
                                mQueue.add(new MethodKey(ci.name, "<clinit>", "()V"));
                            }
                            mQueue.addAll(mi.calls);
                            fQueue.addAll(mi.fields);
                            for (String t : mi.types) {
                                if (classes.containsKey(t)) {
                                    seenTypes.add(t);
                                    addSupertypes(t, seenTypes);
                                }
                            }
                        }
                    }
                    while (!fQueue.isEmpty()) {
                        FieldKey fk = fQueue.removeFirst();
                        FieldKey resolved = resolveField(fk);
                        if (resolved == null || !seenFields.add(resolved)) {
                            continue;
                        }
                        ClassInfo ci = classes.get(resolved.owner);
                        FieldInfo fi = ci.fields.get(resolved.name + ":" + resolved.desc);
                        seenTypes.add(ci.name);
                        addSupertypes(ci.name, seenTypes);
                        update(md, "F:" + ci.name + "." + fi.name + ":" + fi.desc + "=" + fi.ownHash + "\n");
                        if (ci.hasSharedClinit && ci.methods.containsKey("<clinit>()V")) {
                            mQueue.add(new MethodKey(ci.name, "<clinit>", "()V"));
                        }
                        mQueue.addAll(fi.calls);
                        fQueue.addAll(fi.fields);
                        for (String t : fi.types) {
                            if (classes.containsKey(t)) {
                                seenTypes.add(t);
                                addSupertypes(t, seenTypes);
                            }
                        }
                    }
                }
                for (String t : seenTypes) {
                    update(md, "C:" + t + "=" + classes.get(t).headerHash + "\n");
                }
                String result = hex(md);
                sigCache.put(root, result);
                return result;
            }

            void addSupertypes(String typeName, Set<String> out) {
                ClassInfo ci = classes.get(typeName);
                if (ci == null) {
                    return;
                }
                if (ci.superName != null && classes.containsKey(ci.superName) && out.add(ci.superName)) {
                    addSupertypes(ci.superName, out);
                }
                for (String itf : ci.interfaces) {
                    if (classes.containsKey(itf) && out.add(itf)) {
                        addSupertypes(itf, out);
                    }
                }
            }

            List<MethodKey> resolveMethods(MethodKey ref) {
                List<MethodKey> found = new ArrayList<>();
                if (!classes.containsKey(ref.owner)) {
                    return found;
                }
                MethodKey decl = findSuperMethod(ref.owner, ref.name, ref.desc, new HashSet<>());
                if (decl != null) {
                    found.add(decl);
                }
                if (!"<init>".equals(ref.name) && !"<clinit>".equals(ref.name)) {
                    MethodInfo dm = decl == null ? null
                            : classes.get(decl.owner).methods.get(decl.name + decl.desc);
                    if (dm == null || !dm.isStatic()) {
                        Set<String> subs = new TreeSet<>();
                        collectSubtypes(ref.owner, subs);
                        for (String sub : subs) {
                            ClassInfo sc = classes.get(sub);
                            if (sc != null && sc.methods.containsKey(ref.name + ref.desc)) {
                                found.add(new MethodKey(sub, ref.name, ref.desc));
                            }
                        }
                    }
                }
                return found;
            }

            MethodKey findSuperMethod(String owner, String name, String desc, Set<String> visited) {
                if (owner == null || !visited.add(owner)) {
                    return null;
                }
                ClassInfo ci = classes.get(owner);
                if (ci == null) {
                    return null;
                }
                if (ci.methods.containsKey(name + desc)) {
                    return new MethodKey(owner, name, desc);
                }
                MethodKey sup = findSuperMethod(ci.superName, name, desc, visited);
                if (sup != null) {
                    return sup;
                }
                for (String itf : ci.interfaces) {
                    MethodKey im = findSuperMethod(itf, name, desc, visited);
                    if (im != null) {
                        return im;
                    }
                }
                return null;
            }

            void collectSubtypes(String owner, Set<String> out) {
                for (String sub : directSubtypes.getOrDefault(owner, Set.of())) {
                    if (out.add(sub)) {
                        collectSubtypes(sub, out);
                    }
                }
            }

            FieldKey resolveField(FieldKey ref) {
                return findSuperField(ref.owner, ref.name, ref.desc, new HashSet<>());
            }

            FieldKey findSuperField(String owner, String name, String desc, Set<String> visited) {
                if (owner == null || !visited.add(owner)) {
                    return null;
                }
                ClassInfo ci = classes.get(owner);
                if (ci == null) {
                    return null;
                }
                if (ci.fields.containsKey(name + ":" + desc)) {
                    return new FieldKey(owner, name, desc);
                }
                FieldKey sup = findSuperField(ci.superName, name, desc, visited);
                if (sup != null) {
                    return sup;
                }
                for (String itf : ci.interfaces) {
                    FieldKey f = findSuperField(itf, name, desc, visited);
                    if (f != null) {
                        return f;
                    }
                }
                return null;
            }
        }

        static ClassInfo parseClass(byte[] bytes, String where) throws IOException {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt() != 0xCAFEBABE) {
                    throw new IOException("not a class file");
                }
                skip(in, 4);
                Object[] cp = new Object[in.readUnsignedShort()];
                for (int i = 1; i < cp.length; i++) {
                    int tag = in.readUnsignedByte();
                    switch (tag) {
                        case 1 -> cp[i] = in.readUTF();
                        case 3 -> cp[i] = in.readInt();
                        case 4 -> cp[i] = Float.floatToRawIntBits(in.readFloat());
                        case 5 -> {
                            cp[i] = in.readLong();
                            i++;
                        }
                        case 6 -> {
                            cp[i] = Double.doubleToRawLongBits(in.readDouble());
                            i++;
                        }
                        case 7, 19, 20 -> cp[i] = new CpClass(in.readUnsignedShort());
                        case 8 -> cp[i] = new CpString(in.readUnsignedShort());
                        case 9, 10, 11 -> cp[i] = new CpRef(tag, in.readUnsignedShort(), in.readUnsignedShort());
                        case 12 -> cp[i] = new CpNat(in.readUnsignedShort(), in.readUnsignedShort());
                        case 15 -> cp[i] = new CpHandle(in.readUnsignedByte(), in.readUnsignedShort());
                        case 16 -> cp[i] = new CpMethodType(in.readUnsignedShort());
                        case 17, 18 -> cp[i] = new CpIndy(tag, in.readUnsignedShort(), in.readUnsignedShort());
                        default -> throw new IOException("unknown constant pool tag " + tag);
                    }
                }
                int classFlags = in.readUnsignedShort();
                String thisClass = cpClass(cp, in.readUnsignedShort());
                int superIdx = in.readUnsignedShort();
                String superClass = superIdx == 0 ? null : cpClass(cp, superIdx);
                List<String> interfaces = new ArrayList<>();
                for (int n = in.readUnsignedShort(); n > 0; n--) {
                    interfaces.add(cpClass(cp, in.readUnsignedShort()));
                }
                List<RawMember> rawFields = readRawMembers(in, cp);
                List<RawMember> rawMethods = readRawMembers(in, cp);
                List<AttrBytes> classAttrs = readRawAttrs(in, cp);
                List<Bsm> bsms = new ArrayList<>();
                Map<String, String> classAnns = new TreeMap<>();
                for (AttrBytes attr : classAttrs) {
                    if ("BootstrapMethods".equals(attr.name)) {
                        DataInputStream a = new DataInputStream(new ByteArrayInputStream(attr.data));
                        for (int n = a.readUnsignedShort(); n > 0; n--) {
                            int mh = a.readUnsignedShort();
                            List<Integer> args = new ArrayList<>();
                            for (int k = a.readUnsignedShort(); k > 0; k--) {
                                args.add(a.readUnsignedShort());
                            }
                            bsms.add(new Bsm(mh, args));
                        }
                    } else if ("RuntimeVisibleAnnotations".equals(attr.name)
                            || "RuntimeInvisibleAnnotations".equals(attr.name)) {
                        readAnnotations(attr.data, cp, classAnns);
                    }
                }
                Map<String, List<CodeDigest>> clinitSlices = new HashMap<>();
                boolean hasSharedClinit = false;
                for (RawMember rm : rawMethods) {
                    if ("<clinit>".equals(rm.name) && "()V".equals(rm.desc)) {
                        hasSharedClinit = !sliceClinit(rm, thisClass, cp, bsms, clinitSlices);
                    }
                }
                Map<String, FieldInfo> fields = new LinkedHashMap<>();
                for (RawMember rf : rawFields) {
                    Map<String, String> anns = new TreeMap<>();
                    String constVal = "";
                    for (AttrBytes attr : rf.attrs) {
                        if ("RuntimeVisibleAnnotations".equals(attr.name)
                                || "RuntimeInvisibleAnnotations".equals(attr.name)) {
                            readAnnotations(attr.data, cp, anns);
                        } else if ("ConstantValue".equals(attr.name)) {
                            DataInputStream a = new DataInputStream(new ByteArrayInputStream(attr.data));
                            constVal = cpSymbolic(cp, bsms, a.readUnsignedShort(), new ArrayList<>(),
                                    new ArrayList<>(), new TreeSet<>());
                        }
                    }
                    MessageDigest fmd = sha256();
                    update(fmd, (rf.flags & RELEVANT_FIELD_FLAGS) + "|" + rf.name + "|" + rf.desc + "|" + anns
                            + "|" + constVal + "|");
                    List<MethodKey> fCalls = new ArrayList<>();
                    List<FieldKey> fFields = new ArrayList<>();
                    Set<String> fTypes = new TreeSet<>();
                    addDescriptorTypes(rf.desc, fTypes);
                    for (CodeDigest slice : clinitSlices.getOrDefault(rf.name + ":" + rf.desc, List.of())) {
                        update(fmd, slice.hash + ";");
                        fCalls.addAll(slice.calls);
                        fFields.addAll(slice.fields);
                        fTypes.addAll(slice.types);
                    }
                    fields.put(rf.name + ":" + rf.desc,
                            new FieldInfo(rf.flags, rf.name, rf.desc, anns, hex(fmd), fCalls, fFields, fTypes));
                }
                Map<String, MethodInfo> methods = new LinkedHashMap<>();
                boolean nonPureInits = false;
                for (RawMember rm : rawMethods) {
                    Map<String, String> anns = new TreeMap<>();
                    List<String> paramAnns = new ArrayList<>();
                    byte[] codeAttr = null;
                    for (AttrBytes attr : rm.attrs) {
                        switch (attr.name) {
                            case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" ->
                                    readAnnotations(attr.data, cp, anns);
                            case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" ->
                                    paramAnns.add(readParameterAnnotations(attr.data, cp));
                            case "Code" -> codeAttr = attr.data;
                            default -> {
                            }
                        }
                    }
                    CodeDigest cd = codeAttr == null
                            ? new CodeDigest("NO_CODE", List.of(), List.of(), new TreeSet<>())
                            : decodeMethodCode(codeAttr, thisClass, cp, bsms);
                    Set<String> mTypes = new TreeSet<>(cd.types);
                    addDescriptorTypes(rm.desc, mTypes);
                    if ("<init>".equals(rm.name) && (codeAttr == null || !isPureFieldInit(codeAttr, thisClass, superClass, cp))) {
                        nonPureInits = true;
                    }
                    MessageDigest mmd = sha256();
                    String normName = LAMBDA_METHOD.matcher(rm.name).matches() ? "<lambda>" : rm.name;
                    update(mmd, (rm.flags & RELEVANT_METHOD_FLAGS) + "|" + normName + "|" + rm.desc + "|" + anns
                            + "|" + paramAnns + "|" + cd.hash);
                    methods.put(rm.name + rm.desc,
                            new MethodInfo(rm.flags, rm.name, rm.desc, anns, hex(mmd), cd.calls, cd.fields, mTypes));
                }
                MessageDigest hmd = sha256();
                update(hmd, (classFlags & 0x2610) + "|" + thisClass + "|" + Objects.toString(superClass, "")
                        + "|" + interfaces + "|" + classAnns);
                String headerHash = hex(hmd);
                MessageDigest wmd = sha256();
                update(wmd, headerHash + "\n");
                for (FieldInfo fi : fields.values()) {
                    update(wmd, "F:" + fi.name + ":" + fi.desc + "=" + fi.ownHash + "\n");
                }
                for (MethodInfo mi : methods.values()) {
                    update(wmd, "M:" + mi.name + mi.desc + "=" + mi.ownHash + "\n");
                }
                return new ClassInfo(thisClass, superClass, interfaces, classAnns, headerHash, hex(wmd),
                        nonPureInits, hasSharedClinit, fields, methods);
            } catch (IOException | RuntimeException e) {
                throw new IOException("cannot parse glue class " + where + ": " + e.getMessage(), e);
            }
        }

        static List<RawMember> readRawMembers(DataInputStream in, Object[] cp) throws IOException {
            List<RawMember> list = new ArrayList<>();
            for (int count = in.readUnsignedShort(); count > 0; count--) {
                int flags = in.readUnsignedShort();
                String name = cpUtf8(cp, in.readUnsignedShort());
                String desc = cpUtf8(cp, in.readUnsignedShort());
                list.add(new RawMember(flags, name, desc, readRawAttrs(in, cp)));
            }
            return list;
        }

        static List<AttrBytes> readRawAttrs(DataInputStream in, Object[] cp) throws IOException {
            List<AttrBytes> attrs = new ArrayList<>();
            for (int count = in.readUnsignedShort(); count > 0; count--) {
                String name = cpUtf8(cp, in.readUnsignedShort());
                byte[] data = bytes(in, in.readInt());
                attrs.add(new AttrBytes(name, data));
            }
            return attrs;
        }

        static void readAnnotations(byte[] data, Object[] cp, Map<String, String> out) throws IOException {
            DataInputStream a = new DataInputStream(new ByteArrayInputStream(data));
            for (int n = a.readUnsignedShort(); n > 0; n--) {
                String type = cpUtf8(cp, a.readUnsignedShort());
                out.put(type, readElementValuePairs(a, cp));
            }
        }

        static String readParameterAnnotations(byte[] data, Object[] cp) throws IOException {
            DataInputStream a = new DataInputStream(new ByteArrayInputStream(data));
            int params = a.readUnsignedByte();
            StringBuilder sb = new StringBuilder();
            for (int p = 0; p < params; p++) {
                Map<String, String> map = new TreeMap<>();
                for (int n = a.readUnsignedShort(); n > 0; n--) {
                    map.put(cpUtf8(cp, a.readUnsignedShort()), readElementValuePairs(a, cp));
                }
                sb.append(p).append(':').append(map).append(';');
            }
            return sb.toString();
        }

        static String readElementValuePairs(DataInputStream a, Object[] cp) throws IOException {
            Map<String, String> pairs = new TreeMap<>();
            for (int count = a.readUnsignedShort(); count > 0; count--) {
                String name = cpUtf8(cp, a.readUnsignedShort());
                pairs.put(name, readElementValue(a, cp));
            }
            return pairs.toString();
        }

        static String readElementValue(DataInputStream a, Object[] cp) throws IOException {
            int tag = a.readUnsignedByte();
            return switch (tag) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' ->
                        (char) tag + ":" + cpConstValue(cp, a.readUnsignedShort());
                case 'e' -> "e:" + cpUtf8(cp, a.readUnsignedShort()) + "." + cpUtf8(cp, a.readUnsignedShort());
                case '@' -> "@" + cpUtf8(cp, a.readUnsignedShort()) + readElementValuePairs(a, cp);
                case '[' -> {
                    List<String> items = new ArrayList<>();
                    for (int n = a.readUnsignedShort(); n > 0; n--) {
                        items.add(readElementValue(a, cp));
                    }
                    yield items.toString();
                }
                default -> throw new IOException("unknown annotation element tag " + tag);
            };
        }

        static boolean isPureFieldInit(byte[] codeAttr, String thisClass, String superClass, Object[] cp)
                throws IOException {
            if (!"java/lang/Object".equals(superClass)) {
                return false;
            }
            DataInputStream a = new DataInputStream(new ByteArrayInputStream(codeAttr));
            skip(a, 4);
            byte[] code = bytes(a, a.readInt());
            if (a.readUnsignedShort() != 0) {
                return false;
            }
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                if (op >= 0x1A && op <= 0x2D || op == 0xB1) {
                    pc += 1;
                } else if (op >= 0x15 && op <= 0x19) {
                    pc += 2;
                } else if (op == 0xB7) {
                    CpRef ref = (CpRef) cp[u2(code, pc + 1)];
                    CpNat nat = (CpNat) cp[ref.natIdx];
                    if (!"java/lang/Object".equals(cpClass(cp, ref.classIdx))
                            || !"<init>".equals(cpUtf8(cp, nat.nameIdx))
                            || !"()V".equals(cpUtf8(cp, nat.descIdx))) {
                        return false;
                    }
                    pc += 3;
                } else if (op == 0xB5) {
                    CpRef ref = (CpRef) cp[u2(code, pc + 1)];
                    if (!thisClass.equals(cpClass(cp, ref.classIdx))) {
                        return false;
                    }
                    pc += 3;
                } else {
                    return false;
                }
            }
            return true;
        }

        static boolean sliceClinit(RawMember clinit, String thisClass, Object[] cp, List<Bsm> bsms,
                                   Map<String, List<CodeDigest>> out) throws IOException {
            byte[] codeAttr = null;
            for (AttrBytes attr : clinit.attrs) {
                if ("Code".equals(attr.name)) {
                    codeAttr = attr.data;
                }
            }
            if (codeAttr == null) {
                return true;
            }
            DataInputStream a = new DataInputStream(new ByteArrayInputStream(codeAttr));
            skip(a, 4);
            byte[] code = bytes(a, a.readInt());
            if (a.readUnsignedShort() != 0) {
                return false;
            }
            int sliceStart = 0;
            int pc = 0;
            while (pc < code.length) {
                int op = code[pc] & 0xFF;
                if ((op >= 0x99 && op <= 0xAB) || op == 0xBF || (op >= 0xC6 && op <= 0xC9) || op == 0xB5) {
                    return false;
                }
                int len = instructionLength(code, pc);
                if (op == 0xB3) {
                    CpRef ref = (CpRef) cp[u2(code, pc + 1)];
                    if (!thisClass.equals(cpClass(cp, ref.classIdx))) {
                        return false;
                    }
                    CpNat nat = (CpNat) cp[ref.natIdx];
                    String fKey = cpUtf8(cp, nat.nameIdx) + ":" + cpUtf8(cp, nat.descIdx);
                    out.computeIfAbsent(fKey, k -> new ArrayList<>())
                            .add(decodeSlice(code, sliceStart, pc, thisClass, cp, bsms));
                    sliceStart = pc + len;
                } else if (op == 0xB1 && pc + len == code.length && sliceStart == pc) {
                    return true;
                }
                pc += len;
            }
            return sliceStart == code.length;
        }

        static CodeDigest decodeMethodCode(byte[] codeAttr, String thisClass, Object[] cp, List<Bsm> bsms)
                throws IOException {
            DataInputStream a = new DataInputStream(new ByteArrayInputStream(codeAttr));
            skip(a, 4);
            byte[] code = bytes(a, a.readInt());
            CodeDigest body = decodeSlice(code, 0, code.length, thisClass, cp, bsms);
            MessageDigest md = sha256();
            update(md, body.hash + "|exc:");
            Set<String> types = new TreeSet<>(body.types);
            for (int n = a.readUnsignedShort(); n > 0; n--) {
                int spc = a.readUnsignedShort();
                int epc = a.readUnsignedShort();
                int hpc = a.readUnsignedShort();
                int ctype = a.readUnsignedShort();
                String cname = ctype == 0 ? "*" : cpClass(cp, ctype);
                if (ctype != 0) {
                    types.add(cname);
                }
                update(md, spc + "-" + epc + "->" + hpc + ":" + cname + ";");
            }
            return new CodeDigest(hex(md), body.calls, body.fields, types);
        }

        static CodeDigest decodeSlice(byte[] code, int fromPc, int toPc, String thisClass, Object[] cp, List<Bsm> bsms)
                throws IOException {
            MessageDigest md = sha256();
            List<MethodKey> calls = new ArrayList<>();
            List<FieldKey> fields = new ArrayList<>();
            Set<String> types = new TreeSet<>();
            int pc = fromPc;
            while (pc < toPc) {
                int relPc = pc - fromPc;
                int op = code[pc] & 0xFF;
                int len = instructionLength(code, pc);
                switch (op) {
                    case 0x12 -> update(md, relPc + ":ldc:" + cpSymbolic(cp, bsms, code[pc + 1] & 0xFF, calls, fields, types) + ";");
                    case 0x13, 0x14 -> update(md, relPc + ":ldc:" + cpSymbolic(cp, bsms, u2(code, pc + 1), calls, fields, types) + ";");
                    case 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                            0xC6, 0xC7 -> update(md, relPc + ":br" + op + ":" + (relPc + s2(code, pc + 1)) + ";");
                    case 0xC8, 0xC9 -> update(md, relPc + ":brw" + op + ":" + (relPc + s4(code, pc + 1)) + ";");
                    case 0xAA -> {
                        int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                        int def = relPc + s4(code, p);
                        int low = s4(code, p + 4);
                        int high = s4(code, p + 8);
                        update(md, relPc + ":tsw:" + def + ":" + low + ":" + high);
                        int count = high - low + 1;
                        for (int i = 0; i < count; i++) {
                            update(md, "," + (relPc + s4(code, p + 12 + i * 4)));
                        }
                        update(md, ";");
                    }
                    case 0xAB -> {
                        int p = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
                        int def = relPc + s4(code, p);
                        int npairs = s4(code, p + 4);
                        update(md, relPc + ":lsw:" + def + ":" + npairs);
                        for (int i = 0; i < npairs; i++) {
                            update(md, "," + s4(code, p + 8 + i * 8) + "->" + (relPc + s4(code, p + 12 + i * 8)));
                        }
                        update(md, ";");
                    }
                    case 0xB2, 0xB3, 0xB4, 0xB5 -> {
                        CpRef ref = (CpRef) cp[u2(code, pc + 1)];
                        String owner = cpClass(cp, ref.classIdx);
                        CpNat nat = (CpNat) cp[ref.natIdx];
                        String name = cpUtf8(cp, nat.nameIdx);
                        String desc = cpUtf8(cp, nat.descIdx);
                        fields.add(new FieldKey(owner, name, desc));
                        addDescriptorTypes(desc, types);
                        update(md, relPc + ":f" + op + ":" + owner + "." + name + ":" + desc + ";");
                    }
                    case 0xB6, 0xB7, 0xB8, 0xB9 -> {
                        CpRef ref = (CpRef) cp[u2(code, pc + 1)];
                        String owner = cpClass(cp, ref.classIdx);
                        CpNat nat = (CpNat) cp[ref.natIdx];
                        String name = cpUtf8(cp, nat.nameIdx);
                        String desc = cpUtf8(cp, nat.descIdx);
                        calls.add(new MethodKey(owner, name, desc));
                        addDescriptorTypes(desc, types);
                        int normOp = (op == 0xB7 && owner.equals(thisClass) && !"<init>".equals(name)) ? 0xB6 : op;
                        String normName = LAMBDA_METHOD.matcher(name).matches() ? "<lambda>" : name;
                        update(md, relPc + ":m" + normOp + ":" + owner + "." + normName + desc + ";");
                    }
                    case 0xBA -> {
                        CpIndy indy = (CpIndy) cp[u2(code, pc + 1)];
                        CpNat nat = (CpNat) cp[indy.natIdx];
                        String name = cpUtf8(cp, nat.nameIdx);
                        String desc = cpUtf8(cp, nat.descIdx);
                        addDescriptorTypes(desc, types);
                        Bsm bsm = bsms.get(indy.bsmIdx);
                        StringBuilder sb = new StringBuilder();
                        sb.append(relPc).append(":indy:").append(name).append(desc).append(':')
                                .append(cpSymbolic(cp, bsms, bsm.mhIdx, calls, fields, types)).append('[');
                        for (int argIdx : bsm.args) {
                            sb.append(cpSymbolic(cp, bsms, argIdx, calls, fields, types)).append(',');
                        }
                        sb.append("];");
                        update(md, sb.toString());
                    }
                    case 0xBB, 0xBD, 0xC0, 0xC1 -> {
                        String cls = cpClass(cp, u2(code, pc + 1));
                        addInternalType(cls, types);
                        update(md, relPc + ":t" + op + ":" + cls + ";");
                    }
                    case 0xC5 -> {
                        String cls = cpClass(cp, u2(code, pc + 1));
                        addInternalType(cls, types);
                        update(md, relPc + ":multi:" + cls + ":" + (code[pc + 3] & 0xFF) + ";");
                    }
                    default -> {
                        update(md, relPc + ":raw:");
                        md.update(code, pc, len);
                        update(md, ";");
                    }
                }
                pc += len;
            }
            return new CodeDigest(hex(md), calls, fields, types);
        }

        static int instructionLength(byte[] code, int pc) throws IOException {
            int op = code[pc] & 0xFF;
            return switch (op) {
                case 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
                        0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29,
                        0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35,
                        0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A,
                        0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A,
                        0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A,
                        0x6B, 0x6C, 0x6D, 0x6E, 0x6F, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A,
                        0x7B, 0x7C, 0x7D, 0x7E, 0x7F, 0x80, 0x81, 0x82, 0x83,
                        0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92, 0x93, 0x94,
                        0x95, 0x96, 0x97, 0x98, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xBE, 0xBF, 0xC2, 0xC3 -> 1;
                case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC -> 2;
                case 0x11, 0x13, 0x14, 0x84, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4,
                        0xA5, 0xA6, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1,
                        0xC6, 0xC7 -> 3;
                case 0xC5 -> 4;
                case 0xB9, 0xBA, 0xC8, 0xC9 -> 5;
                case 0xC4 -> (code[pc + 1] & 0xFF) == 0x84 ? 6 : 4;
                case 0xAA -> {
                    int pad = (4 - ((pc + 1) & 3)) & 3;
                    int p = pc + 1 + pad;
                    long count = (long) s4(code, p + 8) - (long) s4(code, p + 4) + 1L;
                    yield 1 + pad + 12 + Math.toIntExact(count * 4L);
                }
                case 0xAB -> {
                    int pad = (4 - ((pc + 1) & 3)) & 3;
                    int p = pc + 1 + pad;
                    long npairs = s4(code, p + 4);
                    yield 1 + pad + 8 + Math.toIntExact(npairs * 8L);
                }
                default -> throw new IOException("unknown JVM opcode 0x" + Integer.toHexString(op));
            };
        }

        static String cpSymbolic(Object[] cp, List<Bsm> bsms, int idx, List<MethodKey> calls, List<FieldKey> fields,
                                 Set<String> types) throws IOException {
            Object entry = cp[idx];
            if (entry instanceof String s) {
                return "U:" + s;
            }
            if (entry instanceof Integer n) {
                return "I:" + n;
            }
            if (entry instanceof Long l) {
                return "J:" + l;
            }
            if (entry instanceof CpString s) {
                return "S:" + cpUtf8(cp, s.utf8Idx);
            }
            if (entry instanceof CpClass c) {
                String cls = cpUtf8(cp, c.nameIdx);
                addInternalType(cls, types);
                return "C:" + cls;
            }
            if (entry instanceof CpMethodType mt) {
                String desc = cpUtf8(cp, mt.descIdx);
                addDescriptorTypes(desc, types);
                return "MT:" + desc;
            }
            if (entry instanceof CpHandle mh) {
                CpRef ref = (CpRef) cp[mh.refIdx];
                String owner = cpClass(cp, ref.classIdx);
                CpNat nat = (CpNat) cp[ref.natIdx];
                String name = cpUtf8(cp, nat.nameIdx);
                String desc = cpUtf8(cp, nat.descIdx);
                addDescriptorTypes(desc, types);
                if (mh.kind >= 1 && mh.kind <= 4) {
                    fields.add(new FieldKey(owner, name, desc));
                } else {
                    calls.add(new MethodKey(owner, name, desc));
                }
                String normName = LAMBDA_METHOD.matcher(name).matches() ? "<lambda>" : name;
                return "MH:" + mh.kind + ":" + owner + "." + normName + desc;
            }
            if (entry instanceof CpIndy dyn) {
                CpNat nat = (CpNat) cp[dyn.natIdx];
                Bsm bsm = bsms.get(dyn.bsmIdx);
                StringBuilder sb = new StringBuilder("DYN:").append(cpUtf8(cp, nat.nameIdx))
                        .append(cpUtf8(cp, nat.descIdx)).append(':')
                        .append(cpSymbolic(cp, bsms, bsm.mhIdx, calls, fields, types)).append('[');
                for (int a : bsm.args) {
                    sb.append(cpSymbolic(cp, bsms, a, calls, fields, types)).append(',');
                }
                return sb.append(']').toString();
            }
            throw new IOException("unexpected constant pool entry at " + idx + ": " + entry);
        }

        static String cpConstValue(Object[] cp, int idx) throws IOException {
            Object entry = cp[idx];
            if (entry instanceof String || entry instanceof Integer || entry instanceof Long) {
                return entry.toString();
            }
            if (entry instanceof CpString s) {
                return cpUtf8(cp, s.utf8Idx);
            }
            if (entry instanceof CpClass c) {
                return cpUtf8(cp, c.nameIdx);
            }
            throw new IOException("unexpected annotation constant at " + idx);
        }

        static String cpUtf8(Object[] cp, int idx) throws IOException {
            if (idx > 0 && idx < cp.length && cp[idx] instanceof String s) {
                return s;
            }
            throw new IOException("expected UTF-8 at cp[" + idx + "]");
        }

        static String cpClass(Object[] cp, int idx) throws IOException {
            if (idx > 0 && idx < cp.length && cp[idx] instanceof CpClass c) {
                return cpUtf8(cp, c.nameIdx);
            }
            throw new IOException("expected Class at cp[" + idx + "]");
        }

        static void addInternalType(String internal, Set<String> types) {
            if (internal.startsWith("[")) {
                addDescriptorTypes(internal, types);
            } else {
                types.add(internal);
            }
        }

        static void addDescriptorTypes(String desc, Set<String> types) {
            int i = 0;
            while (i < desc.length()) {
                if (desc.charAt(i) == 'L') {
                    int semi = desc.indexOf(';', i + 1);
                    if (semi > i + 1) {
                        types.add(desc.substring(i + 1, semi));
                        i = semi + 1;
                        continue;
                    }
                }
                i++;
            }
        }

        static int u2(byte[] b, int off) {
            return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        }

        static int s2(byte[] b, int off) {
            return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
        }

        static int s4(byte[] b, int off) {
            return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                    | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
        }

        static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static void update(MessageDigest md, String text) {
            md.update(text.getBytes(StandardCharsets.UTF_8));
        }

        static String hex(MessageDigest md) {
            return HexFormat.of().formatHex(md.digest());
        }
    }
}
