package edtbranchsender;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

/**
 * Отправка пакета изменений: XML объектов, изменённых в текущей ветке относительно базовой,
 * упаковываются в zip и коммитятся в отдельную ветку пакетов. Рабочая копия проекта не трогается:
 * ветка пакетов живёт в собственном клоне в каталоге состояния плагина.
 */
public class PackageSender {

    private static final Pattern TASK = Pattern.compile("([A-Za-zА-Яа-я]+-\\d+[\\w-]*)");
    private static final Pattern FORMAT = Pattern.compile("<MetaDataObject[^>]* version=\"([^\"]+)\"");
    private static final int PUSH_ATTEMPTS = 2;

    /** Параметры отправки (поля страницы настроек). */
    public static class Settings {
        public String project;
        public String base;
        public boolean includeUncommitted;
        public boolean configurationModules;
        public String remote;
        public String targetBranch;
    }

    /** Итог отправки. */
    public static class Outcome {
        public String pathInBranch;
        public String commit;
        public List<String> info = new ArrayList<>();
    }

    /** Приёмник строк журнала. */
    public interface Log {
        void line(String text);
    }

    /** Состояние рабочей копии проекта: для показа на странице и для пакета. */
    public static class SourceState {
        public Path gitRoot;
        public String branch;
        public String commit;
        public boolean dirty;
    }

    private final XmlExporter exporter;

    public PackageSender(XmlExporter exporter) {
        this.exporter = exporter;
    }

    public SourceState sourceState(String project) throws IOException, InterruptedException {
        Path projectDir = exporter.projectDir(project);
        SourceState state = new SourceState();
        state.gitRoot = Path.of(Git.run(projectDir, "rev-parse", "--show-toplevel"));
        state.branch = Git.run(state.gitRoot, "rev-parse", "--abbrev-ref", "HEAD");
        state.commit = Git.run(state.gitRoot, "rev-parse", "--short", "HEAD");
        state.dirty = !Git.run(state.gitRoot, "status", "--porcelain").isEmpty();
        return state;
    }

    public Outcome send(Settings settings, Log log, IProgressMonitor monitor) throws Exception {
        require(settings.project, "проект EDT");
        require(settings.base, "базовая ветка");
        require(settings.remote, "репозиторий для пакетов");
        require(settings.targetBranch, "ветка для пакетов");

        SourceState source = sourceState(settings.project);
        if ("HEAD".equals(source.branch)) {
            throw new IllegalStateException("Рабочая копия не на ветке (detached HEAD) — переключитесь на ветку задачи");
        }
        if (source.branch.equals(settings.targetBranch)) {
            throw new IllegalArgumentException("Ветка пакетов совпадает с текущей веткой проекта: " + source.branch);
        }
        // XML берётся из модели EDT, то есть из файлов рабочей копии: «только закоммиченное» честно
        // получается лишь на чистой рабочей копии.
        if (source.dirty && !settings.includeUncommitted) {
            throw new IllegalStateException("В рабочей копии есть незакоммиченные правки, а XML выгружается из неё."
                + " Закоммитьте или спрячьте правки (stash) либо включите их в пакет.");
        }
        String task = taskName(source.branch);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date());
        // Пакет лежит по пути, совпадающему с именем ветки: feature/X → feature/X.zip; новая отправка
        // перезаписывает файл, прежние версии остаются в истории ветки пакетов.
        String pathInBranch = packagePath(source.branch);
        String name = pathInBranch.substring(pathInBranch.lastIndexOf('/') + 1, pathInBranch.length() - 4);
        log.line("Ветка " + source.branch + ", коммит " + source.commit + (source.dirty ? " (есть незакоммиченные правки)" : ""));

        fetchBase(source.gitRoot, settings.base, log);
        checkBase(source.gitRoot, settings.base);
        int behind = behind(source.gitRoot, settings.base);
        if (behind > 0) {
            throw new IllegalStateException("Ветка отстаёт от " + settings.base + " на " + behind + " коммит(ов)."
                + " Подтяните " + settings.base + " в ветку задачи (merge или rebase) и отправьте снова.");
        }
        // Коммит базы, который ветка содержит: на нём базу можно восстановить перед загрузкой пакета,
        // даже если в базовую ветку с тех пор что-то слили.
        String baseCommit = Git.run(source.gitRoot, "rev-parse", settings.base + "^{commit}");
        String baseDate = Git.run(source.gitRoot, "log", "-1", "--format=%cd", "--date=format:%d.%m.%Y %H:%M",
            baseCommit);
        log.line("Коммит базы: " + baseCommit + " от " + baseDate);
        checkCanceled(monitor);

        Path work = Files.createTempDirectory("branch-sender-");
        try {
            Path xmlDir = work.resolve("xml");
            log.line("Выгрузка изменений относительно " + settings.base + "...");
            XmlExporter.Result result = exporter.exportChanged(settings.project, settings.base, xmlDir,
                settings.includeUncommitted, settings.configurationModules);
            if (result.listFile != null) {
                Files.deleteIfExists(Path.of(result.listFile));
            }
            if (!result.notFound.isEmpty()) {
                throw new IOException("В проекте EDT не найдены объекты: " + String.join(", ", result.notFound));
            }
            if (result.files.isEmpty()) {
                throw new IOException("Изменённых объектов нет — пакет не собран");
            }
            if (!result.ok) {
                throw new IOException("EDT выгрузил с ошибками: " + String.join("; ", result.messages));
            }
            log.line("Выгружено объектов: " + result.exported.size() + ", файлов: " + result.files.size());
            checkCanceled(monitor);

            Outcome outcome = new Outcome();
            outcome.info = info(task, source, settings, baseCommit + " от " + baseDate, result, xmlDir);
            Files.write(work.resolve("info.txt"), withBom(String.join("\r\n", outcome.info) + "\r\n"));
            Path zip = work.resolve(name + ".zip");
            zip(work, zip, xmlDir, work.resolve("info.txt"));
            log.line("Пакет: " + zip.getFileName() + " (" + Math.max(1, Files.size(zip) / 1024) + " КБ)");
            checkCanceled(monitor);

            outcome.pathInBranch = pathInBranch;
            outcome.commit = publish(source, settings, zip, outcome.pathInBranch,
                commitMessage(task, stamp, source, settings, baseCommit, result), log);
            return outcome;
        } finally {
            deleteTree(work);
        }
    }

    /** Если база — ветка удалённого репозитория (origin/develop), сначала обновить её. */
    private static void fetchBase(Path gitRoot, String base, Log log) throws IOException, InterruptedException {
        int slash = base.indexOf('/');
        if (slash <= 0) {
            return;
        }
        String remote = base.substring(0, slash);
        for (String known : Git.run(gitRoot, "remote").split("\\R")) {
            if (known.strip().equals(remote)) {
                log.line("git fetch " + remote + "...");
                Git.network(gitRoot, "fetch", remote);
                return;
            }
        }
    }

    /**
     * На сколько коммитов ветка задачи отстаёт от базы; -1 — базы нет. Отправлять можно только при 0:
     * XML объекта и Configuration.xml берутся целиком из ветки задачи, и без свежей базы загрузка откатила бы
     * чужие правки, а объекты, добавленные в базу позже, выглядели бы удалёнными.
     */
    public static int behind(Path gitRoot, String base) throws InterruptedException {
        try {
            return Integer.parseInt(Git.run(gitRoot, "rev-list", "--count", "HEAD.." + base));
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private static void checkBase(Path gitRoot, String base) throws InterruptedException {
        try {
            Git.run(gitRoot, "rev-parse", "--verify", "--quiet", base + "^{commit}");
        } catch (IOException e) {
            throw new IllegalArgumentException("Ветка сравнения не найдена: " + base
                + ". Проверьте имя (например, origin/develop).");
        }
    }

    /**
     * Коммит zip в ветку пакетов и push. Ветка должна существовать (создаётся пользователем пустой);
     * клон ветки — неглубокий, в каталоге состояния плагина, по одному на пару «репозиторий + ветка».
     */
    private String publish(SourceState source, Settings settings, Path zip, String pathInBranch, String message,
            Log log) throws IOException, InterruptedException {
        String url = remoteUrl(source.gitRoot, settings.remote.strip());
        String branch = settings.targetBranch.strip();
        Path outboxRoot = Activator.getDefault().getStateLocation().toFile().toPath().resolve("outbox");
        Path outbox = outboxRoot.resolve(Integer.toHexString((url + "#" + branch).hashCode()));
        Files.createDirectories(outboxRoot);

        if (!Files.isDirectory(outbox.resolve(".git"))) {
            deleteTree(outbox);
            log.line("Клонирование ветки " + branch + "...");
            try {
                Git.network(outboxRoot, "clone", "--depth", "1", "--single-branch", "--branch", branch, url,
                    outbox.getFileName().toString());
            } catch (IOException e) {
                deleteTree(outbox);
                throw new IOException("Не удалось получить ветку " + branch + " из " + url
                    + ". Ветка создана и отправлена в репозиторий? " + e.getMessage(), e);
            }
        }
        copyIdentity(source.gitRoot, outbox);

        for (int attempt = 1;; attempt++) {
            Git.network(outbox, "fetch", "--depth", "1", "origin", branch);
            Git.run(outbox, "reset", "--hard", "FETCH_HEAD");
            checkNoSources(source, outbox, branch);

            Path target = outbox.resolve(pathInBranch);
            Files.createDirectories(target.getParent());
            Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            Git.run(outbox, "add", "--", pathInBranch);
            if (Git.run(outbox, "status", "--porcelain").isEmpty()) {
                log.line("Пакет не изменился — коммит не нужен");
                return Git.run(outbox, "rev-parse", "--short", "HEAD");
            }
            Git.run(outbox, "commit", "-q", "-m", message);
            log.line("git push " + branch + "...");
            try {
                Git.network(outbox, "push", "origin", "HEAD:refs/heads/" + branch);
                return Git.run(outbox, "rev-parse", "--short", "HEAD");
            } catch (IOException e) {
                if (attempt >= PUSH_ATTEMPTS) {
                    throw e;
                }
                log.line("push отклонён, повтор поверх свежей ветки: " + e.getMessage());
            }
        }
    }

    /** Защита от ошибки в имени ветки: в ветку с исходниками проекта пакет не кладём. */
    private static void checkNoSources(SourceState source, Path outbox, String branch) throws IOException {
        try (Stream<Path> top = Files.list(outbox)) {
            boolean hasProject = top.anyMatch(p -> !p.getFileName().toString().equals(".git")
                && Files.isDirectory(p.resolve("src")));
            if (hasProject || Files.exists(outbox.resolve(".project"))) {
                throw new IOException("В ветке " + branch + " лежат исходники проекта — это не ветка пакетов");
            }
        }
    }

    private static String remoteUrl(Path gitRoot, String remote) throws IOException, InterruptedException {
        for (String known : Git.run(gitRoot, "remote").split("\\R")) {
            if (known.strip().equals(remote)) {
                return Git.run(gitRoot, "remote", "get-url", remote);
            }
        }
        return remote;
    }

    /** Автор коммита — тот же, что в рабочей копии проекта. */
    private static void copyIdentity(Path gitRoot, Path outbox) throws IOException, InterruptedException {
        for (String key : new String[] {"user.name", "user.email"}) {
            String value;
            try {
                value = Git.run(gitRoot, "config", key);
            } catch (IOException e) {
                continue;
            }
            if (!value.isEmpty()) {
                Git.run(outbox, "config", key, value);
            }
        }
    }

    private static List<String> info(String task, SourceState source, Settings settings, String baseCommit,
            XmlExporter.Result result, Path xmlDir) throws IOException {
        List<String> info = new ArrayList<>();
        info.add("Пакет изменений: " + task);
        info.add("Ветка: " + source.branch + ", коммит " + source.commit
            + (source.dirty && settings.includeUncommitted ? " + незакоммиченные правки" : ""));
        info.add("Сравнение с: " + settings.base);
        info.add("Коммит базы: " + baseCommit);
        info.add("Собран: " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date()));
        String format = formatVersion(xmlDir);
        if (format != null) {
            info.add("Формат выгрузки: " + format);
        }
        info.add("Объектов: " + result.exported.size() + ", файлов: " + result.files.size());
        info.add("");
        info.add("Объекты:");
        result.exported.forEach(o -> info.add("  " + o));
        if (!result.deleted.isEmpty()) {
            info.add("");
            info.add("ВНИМАНИЕ — удалены в EDT, частичной загрузкой НЕ удаляются, удалить в конфигураторе вручную:");
            result.deleted.forEach(o -> info.add("  " + o));
        }
        if (!result.messages.isEmpty()) {
            info.add("");
            info.add("Сообщения EDT при выгрузке:");
            result.messages.forEach(m -> info.add("  " + m));
        }
        return info;
    }

    private static String commitMessage(String task, String stamp, SourceState source, Settings settings,
            String baseCommit, XmlExporter.Result result) {
        return task + ": пакет " + stamp + "\n\n"
            + "Ветка " + source.branch + ", коммит " + source.commit
            + (source.dirty && settings.includeUncommitted ? " + незакоммиченные правки" : "") + "\n"
            + "Сравнение с " + settings.base + ", объектов: " + result.exported.size() + "\n"
            + "Коммит базы: " + baseCommit + "\n";
    }

    private static String formatVersion(Path xmlDir) throws IOException {
        Path configuration = xmlDir.resolve("Configuration.xml");
        if (!Files.isRegularFile(configuration)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(configuration, StandardCharsets.UTF_8)) {
            for (String line : (Iterable<String>) lines.limit(3)::iterator) {
                Matcher matcher = FORMAT.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    private static void zip(Path root, Path zip, Path... sources) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path source : sources) {
                try (Stream<Path> walk = Files.walk(source)) {
                    for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                        zos.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    private static byte[] withBom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        return bytes;
    }

    /** Путь пакета в ветке пакетов: имя ветки, символы, недопустимые в именах файлов Windows, — на «_». */
    private static String packagePath(String branch) {
        return branch.replaceAll("[\\\\:*?\"<>|]", "_") + ".zip";
    }

    private static String taskName(String branch) {
        Matcher matcher = TASK.matcher(branch);
        return matcher.find() ? matcher.group(1) : branch.replaceAll("[\\\\/]", "_");
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не заполнено: " + what);
        }
    }

    private static void checkCanceled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true);
                Files.delete(path);
            }
        }
    }
}
