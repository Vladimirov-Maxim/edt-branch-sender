package edtbranchsender;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;

import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.IExportOperation;
import com._1c.g5.v8.dt.export.IExportStrategy;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Выгрузка выбранных объектов проекта EDT в XML-файлы конфигуратора.
 */
public class XmlExporter {

    private static final Pattern MDO_TYPE = Pattern.compile("<mdclass:(\\w+)\\b");
    private static final Map<String, String> SUBORDINATE_FEATURES = Map.of(
        "Form", "forms",
        "Template", "templates",
        "Command", "commands");

    private final Activator activator;

    public XmlExporter(Activator activator) {
        this.activator = activator;
    }

    /** Результат выгрузки: список файлов, предупреждения, объекты, которые выгрузить нельзя. */
    public static class Result {
        public final List<String> files = new ArrayList<>();
        public final List<String> exported = new ArrayList<>();
        public final List<String> notFound = new ArrayList<>();
        public final List<String> deleted = new ArrayList<>();
        public final List<String> messages = new ArrayList<>();
        public String listFile;
        public String targetDir;
        public boolean ok = true;
    }

    /**
     * Выгружает объекты по полным именам вида {@code Document.Имя}, {@code Document.Имя.Form.Имя},
     * {@code Configuration}.
     */
    public Result exportObjects(String projectName, List<String> names, Path targetDir,
            boolean configurationModules) throws IOException {
        Result result = new Result();
        IV8Project project = findProject(projectName);
        Configuration configuration = ((IConfigurationAware) project).getConfiguration();

        List<EObject> objects = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            EObject object = resolve(configuration, name.trim());
            if (object == null) {
                result.notFound.add(name);
            } else {
                objects.add(object);
                result.exported.add(name);
            }
        }
        if (objects.isEmpty()) {
            result.ok = result.notFound.isEmpty();
            result.messages.add("Нечего выгружать");
            return result;
        }

        prepareTarget(targetDir);
        IExportOperation operation = activator.getExportOperationFactory().createExportOperation(
            targetDir, project.getVersion(), new PartialStrategy(configurationModules),
            objects.toArray(new EObject[0]));
        IStatus status = operation.run(new NullProgressMonitor());
        collectMessages(status, result.messages);
        result.ok = status.getSeverity() < IStatus.ERROR && result.notFound.isEmpty();
        if (!configurationModules) {
            removeConfigurationRootFiles(targetDir);
        }

        result.targetDir = targetDir.toString();
        collectFiles(targetDir, result);
        return result;
    }

    /**
     * Выгружает объекты, изменённые в git относительно базовой ветки (коммиты ветки задачи
     * и незакоммиченные правки).
     */
    public Result exportChanged(String projectName, String base, Path targetDir, boolean includeUncommitted,
            boolean configurationModules) throws IOException, InterruptedException {
        Path projectDir = projectDir(projectName);
        Path srcDir = projectDir.resolve("src");

        Map<String, String> objects = new LinkedHashMap<>();
        List<String> deleted = new ArrayList<>();
        for (String key : changedObjectFolders(projectName, base + "...HEAD", includeUncommitted)) {
            if ("Configuration".equals(key)) {
                objects.put("Configuration", "Configuration");
                continue;
            }
            int slash = key.indexOf('/');
            String name = key.substring(slash + 1);
            String type = mdoType(srcDir.resolve(key).resolve(name + ".mdo"));
            if (type == null) {
                deleted.add(key);
            } else {
                objects.put(type + "." + name, type + "." + name);
            }
        }

        Result result = exportObjects(projectName, new ArrayList<>(objects.keySet()), targetDir, configurationModules);
        result.deleted.addAll(new LinkedHashSet<>(deleted));
        return result;
    }

    /**
     * Объекты, затронутые изменениями в диапазоне git, в виде каталогов {@code src/}: {@code Documents/Имя},
     * {@code Configuration}. Незакоммиченные правки добавляются по флагу.
     */
    private Set<String> changedObjectFolders(String projectName, String range, boolean includeUncommitted)
            throws IOException, InterruptedException {
        Path srcDir = projectDir(projectName).resolve("src");
        Path gitRoot = Path.of(git(srcDir.getParent(), "rev-parse", "--show-toplevel").trim());

        Set<String> paths = new TreeSet<>();
        for (String line : lines(git(gitRoot, "-c", "core.quotepath=false", "diff", "--name-only", range))) {
            paths.add(unquote(line));
        }
        if (includeUncommitted) {
            for (String line : lines(git(gitRoot, "-c", "core.quotepath=false", "status", "--porcelain", "-uall"))) {
                String path = line.substring(3);
                int arrow = path.indexOf(" -> ");
                if (arrow >= 0) {
                    path = path.substring(arrow + 4);
                }
                paths.add(unquote(path));
            }
        }

        Set<String> folders = new TreeSet<>();
        for (String path : paths) {
            Path file = gitRoot.resolve(path).normalize();
            if (!file.startsWith(srcDir)) {
                continue;
            }
            Path rel = srcDir.relativize(file);
            if (rel.getNameCount() < 2) {
                continue;
            }
            String folder = rel.getName(0).toString();
            folders.add("Configuration".equals(folder) ? folder : folder + "/" + rel.getName(1));
        }
        return folders;
    }

    /** Имена проектов конфигураций в рабочей области (без расширений и внешних обработок). */
    public List<String> configurationProjects() {
        IV8ProjectManager manager = activator.getService(IV8ProjectManager.class);
        if (manager == null) {
            return List.of();
        }
        return manager.getProjects(IConfigurationProject.class).stream()
            .map(p -> p.getProject().getName())
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    /** Каталог проекта EDT на диске (внутри рабочей копии git). */
    public Path projectDir(String projectName) {
        return findProject(projectName).getProject().getLocation().toFile().toPath();
    }

    private IV8Project findProject(String projectName) {
        IV8ProjectManager manager = activator.getService(IV8ProjectManager.class);
        if (manager == null) {
            throw new IllegalStateException("Сервис проектов EDT недоступен");
        }
        IV8Project project = manager.getProject(projectName);
        if (!(project instanceof IConfigurationAware)) {
            throw new IllegalArgumentException("Проект конфигурации не найден: " + projectName);
        }
        return project;
    }

    private static EObject resolve(Configuration configuration, String fullName) {
        if ("Configuration".equals(fullName)) {
            return configuration;
        }
        String[] parts = fullName.split("\\.");
        if (parts.length != 2 && parts.length != 4) {
            return null;
        }
        EObject owner = findInReferences(configuration, parts[0], parts[1], null);
        if (owner == null || parts.length == 2) {
            return owner;
        }
        String feature = SUBORDINATE_FEATURES.get(parts[2]);
        return feature == null ? null : findInReferences(owner, null, parts[3], feature);
    }

    /** Ищет объект по имени в списочных ссылках владельца: по имени ссылки или по типу элементов. */
    private static EObject findInReferences(EObject owner, String typeName, String name, String featureName) {
        for (EReference reference : owner.eClass().getEAllReferences()) {
            if (!reference.isMany()) {
                continue;
            }
            boolean match = featureName != null
                ? featureName.equals(reference.getName())
                : typeName.equals(reference.getEReferenceType().getName());
            if (!match) {
                continue;
            }
            for (Object item : (List<?>) owner.eGet(reference)) {
                if (item instanceof MdObject && name.equals(((MdObject) item).getName())) {
                    return (EObject) item;
                }
            }
        }
        return null;
    }

    private static String mdoType(Path mdo) throws IOException {
        if (!Files.isRegularFile(mdo)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(mdo, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 5; i++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                Matcher matcher = MDO_TYPE.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    private static void prepareTarget(Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            try (Stream<Path> content = Files.list(targetDir)) {
                if (content.findAny().isPresent()) {
                    throw new IOException("Каталог выгрузки не пуст: " + targetDir);
                }
            }
        }
        Files.createDirectories(targetDir);
    }

    /**
     * Корневой каталог Ext конфигурации (MobileClientSignature.bin и т. п.) EDT пишет вместе с Configuration.xml
     * независимо от стратегии экспорта; при частичной загрузке он перезаписал бы файлы корня в базе.
     */
    private static void removeConfigurationRootFiles(Path targetDir) throws IOException {
        Path ext = targetDir.resolve("Ext");
        if (!Files.isDirectory(ext)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(ext)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void collectFiles(Path targetDir, Result result) throws IOException {
        try (Stream<Path> walk = Files.walk(targetDir)) {
            walk.filter(Files::isRegularFile)
                .map(p -> targetDir.relativize(p).toString())
                .sorted()
                .forEach(result.files::add);
        }
        Path listFile = targetDir.getParent() == null
            ? targetDir.resolve("list.txt")
            : targetDir.resolveSibling(targetDir.getFileName() + ".list.txt");
        List<String> absolute = new ArrayList<>();
        for (String file : result.files) {
            absolute.add(targetDir.resolve(file).toString());
        }
        Files.write(listFile, absolute, StandardCharsets.UTF_8);
        result.listFile = listFile.toString();
    }

    private static void collectMessages(IStatus status, List<String> messages) {
        if (status.isMultiStatus()) {
            for (IStatus child : status.getChildren()) {
                collectMessages(child, messages);
            }
        } else if (!status.isOK()) {
            String text = status.getMessage();
            if (status.getException() != null) {
                text += " (" + status.getException() + ")";
            }
            messages.add(text);
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        return Git.run(dir, args);
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    private static String unquote(String path) {
        return path.length() > 1 && path.startsWith("\"") && path.endsWith("\"")
            ? path.substring(1, path.length() - 1)
            : path;
    }

    /**
     * Корень конфигурации выгружается без подчинённых объектов: иначе за ним уходит вся конфигурация.
     * Модули приложения и прочие внешние свойства корня — только по явному запросу.
     */
    private static class PartialStrategy implements IExportStrategy {

        private final boolean configurationModules;

        PartialStrategy(boolean configurationModules) {
            this.configurationModules = configurationModules;
        }

        @Override
        public boolean exportSubordinatesObjects(EObject object) {
            return !(object instanceof Configuration);
        }

        @Override
        public boolean exportExternalProperties(EObject object) {
            return !(object instanceof Configuration) || configurationModules;
        }

        @Override
        public boolean exportUnknown() {
            return false;
        }
    }
}
