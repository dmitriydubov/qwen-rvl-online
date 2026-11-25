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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
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
 *      --out build/gherkin-mutation/mutants [--only-file changed-features]
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
 * {@code --features} is an error (exit 2), never a run over nothing. {@code --glue} names one entry of the classpath
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
                        opts.containsKey("--only-file") ? pathList(Path.of(opts.get("--only-file"))) : null, out);
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
}
