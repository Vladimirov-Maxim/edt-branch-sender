package edtbranchsender;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Запуск системного git. Учётные данные для fetch/push берутся из настроек git пользователя
 * (Git Credential Manager); консольный запрос пароля отключён, чтобы процесс не повис.
 */
final class Git {

    private static final int LOCAL_TIMEOUT = 60;
    private static final int NETWORK_TIMEOUT = 600;

    private Git() {
    }

    /** Локальная команда: diff, status, commit. */
    static String run(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, LOCAL_TIMEOUT, args);
    }

    /** Сетевая команда: clone, fetch, push. */
    static String network(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, NETWORK_TIMEOUT, args);
    }

    private static String run(Path dir, int timeoutSeconds, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        process.getOutputStream().close();

        // Вывод читается в отдельном потоке: иначе тайм-аут не сработает, пока git держит канал.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(buffer);
            } catch (IOException e) {
                // процесс завершён принудительно
            }
        }, "git-output");
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("git не ответил за " + timeoutSeconds + " с: " + String.join(" ", args));
        }
        reader.join(5000);
        String output = buffer.toString(StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " — код " + process.exitValue() + ": " + output);
        }
        return output;
    }

    private static String executable() {
        String configured = System.getProperty("edtbranchsender.git");
        if (configured != null) {
            return configured;
        }
        Path standard = Path.of("C:\\Program Files\\Git\\cmd\\git.exe");
        return Files.isRegularFile(standard) ? standard.toString() : "git";
    }
}
