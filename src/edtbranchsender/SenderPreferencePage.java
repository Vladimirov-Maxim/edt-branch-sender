package edtbranchsender;

import java.io.IOException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;

/**
 * Страница «Окно → Параметры → Пакет изменений»: настройки и кнопка отправки пакета текущей ветки.
 */
public class SenderPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final String PROJECT = "project";
    private static final String BASE = "base";
    private static final String INCLUDE_UNCOMMITTED = "includeUncommitted";
    private static final String CONFIGURATION_MODULES = "configurationModules";
    private static final String REMOTE = "remote";
    private static final String TARGET_BRANCH = "targetBranch";

    /** Одна отправка за раз, даже если страницу открыли заново. */
    private static volatile boolean running;

    private Combo project;
    private Text base;
    private Label branch;
    private Label sync;
    /** Ветка задачи содержит базу (по последнему fetch); без этого кнопка неактивна. */
    private boolean synced;
    private Button includeUncommitted;
    private Button configurationModules;
    private Text remote;
    private Text targetBranch;
    private Button send;
    private Label status;
    private Text log;

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Отправка XML изменений текущей ветки проекта в ветку пакетов git");
        IPreferenceStore store = getPreferenceStore();
        store.setDefault(PROJECT, "");
        store.setDefault(BASE, "origin/develop");
        store.setDefault(INCLUDE_UNCOMMITTED, true);
        store.setDefault(CONFIGURATION_MODULES, false);
        store.setDefault(REMOTE, "origin");
        store.setDefault(TARGET_BRANCH, "");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite page = new Composite(parent, SWT.NONE);
        page.setLayout(new GridLayout(1, false));

        Group source = group(page, "Что отправить");
        new Label(source, SWT.NONE).setText("Проект EDT:");
        project = new Combo(source, SWT.READ_ONLY);
        project.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        project.setItems(new XmlExporter(Activator.getDefault()).configurationProjects().toArray(new String[0]));
        base = field(source, "Сравнивать с:");
        new Label(source, SWT.NONE).setText("Ветка задачи:");
        branch = new Label(source, SWT.WRAP);
        branch.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(source, SWT.NONE).setText("Синхронизация:");
        sync = new Label(source, SWT.WRAP);
        sync.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        includeUncommitted = check(source, "Включать незакоммиченные правки");
        configurationModules = check(source, "Включать модули конфигурации (модуль приложения, сеанса и т. п.)");

        Group target = group(page, "Куда отправить");
        remote = field(target, "Репозиторий:");
        remote.setToolTipText("Имя удалённого репозитория рабочей копии (origin) или URL отдельного репозитория");
        targetBranch = field(target, "Ветка пакетов:");
        targetBranch.setToolTipText("Существующая ветка без исходников проекта; пакет кладётся как <имя ветки задачи>.zip");

        Composite actions = new Composite(page, SWT.NONE);
        actions.setLayout(new GridLayout(3, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        send = new Button(actions, SWT.PUSH);
        send.setText("Отправить");
        send.addListener(SWT.Selection, e -> startSend());
        Button refresh = new Button(actions, SWT.PUSH);
        refresh.setText("Обновить");
        refresh.setToolTipText("Перечитать ветку, правки и синхронизацию с базой (например, после merge)");
        refresh.addListener(SWT.Selection, e -> refreshBranch());
        status = new Label(actions, SWT.WRAP);
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        log = new Text(page, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        GridData logData = new GridData(SWT.FILL, SWT.FILL, true, true);
        logData.heightHint = 160;
        logData.widthHint = 500;
        log.setLayoutData(logData);

        load(false);
        project.addListener(SWT.Selection, e -> refreshBranch());
        base.addListener(SWT.FocusOut, e -> refreshBranch());
        refreshBranch();
        if (running) {
            status.setText("Идёт отправка...");
        }
        return page;
    }

    private static Group group(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return group;
    }

    private static Text field(Composite parent, String label) {
        new Label(parent, SWT.NONE).setText(label);
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return text;
    }

    private static Button check(Composite parent, String label) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(label);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        return button;
    }

    private void load(boolean defaults) {
        IPreferenceStore store = getPreferenceStore();
        selectProject(defaults ? store.getDefaultString(PROJECT) : store.getString(PROJECT));
        base.setText(defaults ? store.getDefaultString(BASE) : store.getString(BASE));        includeUncommitted.setSelection(
            defaults ? store.getDefaultBoolean(INCLUDE_UNCOMMITTED) : store.getBoolean(INCLUDE_UNCOMMITTED));
        configurationModules.setSelection(
            defaults ? store.getDefaultBoolean(CONFIGURATION_MODULES) : store.getBoolean(CONFIGURATION_MODULES));
        remote.setText(defaults ? store.getDefaultString(REMOTE) : store.getString(REMOTE));
        targetBranch.setText(defaults ? store.getDefaultString(TARGET_BRANCH) : store.getString(TARGET_BRANCH));
    }

    /** Сохранённый проект, если он есть в рабочей области, иначе первый из списка. */
    private void selectProject(String name) {
        int index = project.indexOf(name);
        if (index < 0 && project.getItemCount() > 0) {
            index = 0;
        }
        if (index >= 0) {
            project.select(index);
        }
    }

    private void save() {
        IPreferenceStore store = getPreferenceStore();
        store.setValue(PROJECT, project.getText().strip());
        store.setValue(BASE, base.getText().strip());        store.setValue(INCLUDE_UNCOMMITTED, includeUncommitted.getSelection());
        store.setValue(CONFIGURATION_MODULES, configurationModules.getSelection());
        store.setValue(REMOTE, remote.getText().strip());
        store.setValue(TARGET_BRANCH, targetBranch.getText().strip());
        if (store instanceof IPersistentPreferenceStore persistent) {
            try {
                persistent.save();
            } catch (IOException e) {
                Activator.logError("Не удалось сохранить настройки отправки пакетов", e);
            }
        }
    }

    @Override
    public boolean performOk() {
        save();
        return true;
    }

    @Override
    protected void performDefaults() {
        load(true);
        refreshBranch();
        super.performDefaults();
    }

    private void refreshBranch() {
        if (project.getText().isEmpty()) {
            branch.setText("в рабочей области нет проектов конфигурации");
            synced = false;
            updateSendButton();
            branch.getParent().layout(true);
            return;
        }
        synced = false;
        try {
            PackageSender.SourceState state = sender().sourceState(project.getText().strip());
            branch.setText(state.branch + ", коммит " + state.commit + (state.dirty ? " — есть незакоммиченные правки" : ""));
            String baseName = base.getText().strip();
            int behind = PackageSender.behind(state.gitRoot, baseName);
            if (behind < 0) {
                sync.setText("ветка сравнения " + baseName + " не найдена");
            } else if (behind > 0) {
                sync.setText("отстаёт от " + baseName + " на " + behind
                    + " коммит(ов) — подтяните " + baseName + " в ветку задачи, иначе отправка недоступна");
            } else {
                synced = true;
                sync.setText("содержит " + baseName + " (по последнему fetch; при отправке проверяется заново)");
            }
        } catch (Exception e) {
            branch.setText("не определена: " + e.getMessage());
            sync.setText("");
        }
        updateSendButton();
        branch.getParent().layout(true);
    }

    private void updateSendButton() {
        if (send != null && !send.isDisposed()) {
            send.setEnabled(!running && synced);
        }
    }

    private static PackageSender sender() {
        return new PackageSender(new XmlExporter(Activator.getDefault()));
    }

    private void startSend() {
        if (running) {
            return;
        }
        save();
        refreshBranch();
        if (!synced) {
            return;
        }
        PackageSender.Settings settings = new PackageSender.Settings();
        settings.project = project.getText().strip();
        settings.base = base.getText().strip();
        settings.includeUncommitted = includeUncommitted.getSelection();
        settings.configurationModules = configurationModules.getSelection();
        settings.remote = remote.getText().strip();
        settings.targetBranch = targetBranch.getText().strip();

        running = true;
        send.setEnabled(false);
        status.setText("Идёт отправка...");
        log.setText("");
        Display display = getShell().getDisplay();

        Job job = new Job("Отправка пакета изменений") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                String result;
                boolean ok = false;
                try {
                    PackageSender.Outcome outcome = sender().send(settings, text -> ui(display, () -> append(text)),
                        monitor);
                    result = "Отправлено: " + settings.targetBranch + "/" + outcome.pathInBranch + " (коммит "
                        + outcome.commit + ")";
                    ui(display, () -> {
                        append("");
                        outcome.info.forEach(SenderPreferencePage.this::append);
                    });
                    ok = true;
                    Activator.logInfo(result);
                } catch (OperationCanceledException e) {
                    result = "Отправка отменена";
                } catch (Exception e) {
                    result = "Ошибка: " + e.getMessage();
                    Activator.logError("Отправка пакета изменений не удалась", e);
                } finally {
                    running = false;
                }
                String message = result;
                boolean success = ok;
                ui(display, () -> finish(message, success));
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
    }

    private static void ui(Display display, Runnable action) {
        if (!display.isDisposed()) {
            display.asyncExec(action);
        }
    }

    private void append(String text) {
        if (log != null && !log.isDisposed()) {
            log.append(text + System.lineSeparator());
        }
    }

    /** Итог — на странице, а если её уже закрыли — отдельным сообщением. */
    private void finish(String message, boolean success) {
        if (status != null && !status.isDisposed()) {
            status.setText(message);
            status.getParent().layout(true);
            refreshBranch();
            append(message);
            return;
        }
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return;
        }
        if (success) {
            MessageDialog.openInformation(window.getShell(), "Пакет изменений", message);
        } else {
            MessageDialog.openError(window.getShell(), "Пакет изменений", message);
        }
    }
}
