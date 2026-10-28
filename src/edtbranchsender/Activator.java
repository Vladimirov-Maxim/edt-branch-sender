package edtbranchsender;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com._1c.g5.v8.dt.export.ExportRuntimeModule;
import com._1c.g5.v8.dt.export.ExternalDependenciesModule;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * Активатор бандла: держит Guice-инжектор экспорта EDT и настройки отправки пакетов.
 * Фабрика экспорта не публикуется EDT как OSGi-сервис, поэтому инжектор
 * собирается из публичных модулей так же, как это делает мастер экспорта EDT.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "edtbranchsender";

    private static Activator plugin;

    private Injector injector;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public synchronized Injector getInjector() {
        if (injector == null) {
            injector = Guice.createInjector(new ExportRuntimeModule(), new ExternalDependenciesModule(this));
        }
        return injector;
    }

    public IExportOperationFactory getExportOperationFactory() {
        return getInjector().getInstance(IExportOperationFactory.class);
    }

    public <T> T getService(Class<T> type) {
        BundleContext context = getBundle().getBundleContext();
        var ref = context.getServiceReference(type);
        return ref == null ? null : context.getService(ref);
    }

    public static void logInfo(String message) {
        log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    public static void logError(String message, Throwable e) {
        log(new Status(IStatus.ERROR, PLUGIN_ID, message, e));
    }

    private static void log(IStatus status) {
        if (plugin != null) {
            plugin.getLog().log(status);
        }
    }
}
