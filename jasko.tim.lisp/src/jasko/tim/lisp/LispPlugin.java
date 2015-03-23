package jasko.tim.lisp;

import jasko.tim.lisp.preferences.PreferenceConstants;
import jasko.tim.lisp.swank.SwankInterface;
import jasko.tim.lisp.views.ReplView;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Properties;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * The main plugin class to be used in the desktop.
 */
public class LispPlugin extends AbstractUIPlugin {

   // strings to store configurations
   public static final String ATTR_LISP_EXE                = "jasko.tim.lisp"
                                                                    + ".ATTR_LISP_EXE";

   public static final String ATTR_LISP_FLAVOR             = "jasko.tim.lisp"
                                                                    + ".ATTR_LISP_FLAVOR";

   public static final String ID_LAUNCH_CONFIGURATION_TYPE = "jasko.tim.lisp.launchType";
   private static String      CONSOLE_NAME                 = "jasko.tim.lisp.console";    //$NON-NLS-1$

   private static String      CUSP_VERSION                 = "0.0.0";                     //$NON-NLS-1$

   // The shared instance.
   private static LispPlugin  plugin;
   private static String      RELEASE_DATE                 = "0000.00.00";                //$NON-NLS-1$

   /**
    * Returns the shared instance.
    */
   public static LispPlugin getDefault() {
      return LispPlugin.plugin;
   }

   /**
    * Returns an image descriptor for the image file at the given plug-in
    * relative path.
    * 
    * @param path
    *           the path
    * @return the image descriptor
    */
   public static ImageDescriptor getImageDescriptor(final String path) {
      return AbstractUIPlugin.imageDescriptorFromPlugin("jasko.tim.lisp", path); //$NON-NLS-1$
   }

   public static String getReleaseDate() {
      return LispPlugin.RELEASE_DATE;
   }

   public static String getVersion() {
      return LispPlugin.CUSP_VERSION;
   }

   static public String makeStatusMsg(final String lispVersion, final String pkg) {
      final String statusMsg = "CL:" + lispVersion + "| Cusp: " +
               LispPlugin.getVersion() + "| Current package: " + pkg;
      return statusMsg;
   }

   private ColorManager   cm;

   private SwankInterface swank = null;

   /**
    * The constructor.
    */
   public LispPlugin() {
      LispPlugin.plugin = this;
   }

   public void activateConsole() {
      getConsole().activate();
   }

   public ColorManager getColorManager() {
      return this.cm;
   }

   public ArrayList<File> getLibsPath() {
      final String path = getPluginPath() + "libraries"; //$NON-NLS-1$

      // This filter only returns directories of type jasko.tim.lisp.libs
      final FileFilter libPluginFilter = new FileFilter() {
         @Override
         public boolean accept(final File file) {
            return (file.isDirectory() && file.toString()
                                              .matches(".*jasko\\.tim\\.lisp\\.libs.*")); //$NON-NLS-1$
         }
      };

      final ArrayList<File> topLevelDirs = new ArrayList<File>();
      topLevelDirs.add(new File(path));

      final String sysdirs[] = getPreferenceStore().getString(PreferenceConstants.SYSTEMS_PATH)
                                                   .split(";"); //$NON-NLS-1$

      for (final String sysdir : sysdirs) {
         if ((sysdir != null) && !sysdir.equals("")) { //$NON-NLS-1$
            topLevelDirs.add(new File(sysdir));
         }
      }

      final File pluginsDir = (new File(LispPlugin.getDefault().getPluginPath())).getParentFile();
      for (final File dir : pluginsDir.listFiles(libPluginFilter)) {
         topLevelDirs.add(new File(dir.getAbsolutePath() + "/libs")); //$NON-NLS-1$
      }

      // This filter only returns directories
      final FileFilter dirFilter = new FileFilter() {
         @Override
         public boolean accept(final File file) {
            return file.isDirectory();
         }
      };

      final ArrayList<File> subdirs = new ArrayList<File>();
      for (final File dir : topLevelDirs) {
         if (dir.isDirectory()) {
            subdirs.add(dir);
            for (final File subdir : dir.listFiles(dirFilter)) {
               if ((subdir != null) && !subdirs.contains(subdir)) {
                  subdirs.add(subdir);
               }
            }
         }
      }
      if (subdirs.size() == 0) {
         // Either dir does not exist or is not a directory
         System.out.println("*libraries dir not found! " + path); //$NON-NLS-1$
      }
      return subdirs;
   }

   public String getLibsPathRegisterCode() {
      String code = ""; //$NON-NLS-1$
      final ArrayList<File> subdirs = getLibsPath();
      if (subdirs.size() > 0) {
         // code =
         // "(mapcar #'com.gigamonkeys.asdf-extensions:register-source-directory '(\n";
         // // $NON-NLS-1$
         code = "(com.gigamonkeys.asdf-extensions:register-source-directories '(\n"; // $NON-NLS-1$
         for (int i = 0; i < subdirs.size(); i++ ) {
            final File child = subdirs.get(i);
            String name = child.getAbsolutePath().replace("\\", "/"); //$NON-NLS-1$ //$NON-NLS-2$
            if ( !name.endsWith("/")) { //$NON-NLS-1$
               name += "/"; //$NON-NLS-1$
            }
            code += "  \"" + name + "\"\n"; //$NON-NLS-1$ //$NON-NLS-2$
         }
         code += "))"; //$NON-NLS-1$
      }
      return code;
   }

   public String getPluginPath() {
      try {
         String path = FileLocator.resolve(Platform.getBundle("jasko.tim.lisp").getEntry("/")).getFile(); //$NON-NLS-1$ //$NON-NLS-2$
         if (System.getProperty("os.name").toLowerCase().contains("windows")) { //$NON-NLS-1$ //$NON-NLS-2$
            if (path.matches("/\\w:/.*")) { //$NON-NLS-1$
               path = path.substring(1);
            }
         }
         return path;
      }
      catch (final IOException e) {
         e.printStackTrace();
      }
      return ""; //$NON-NLS-1$
   }

   
   public SwankInterface getSwank() throws SwankNotFoundException {
      if ( this.swank == null ) {
         throw new SwankNotFoundException(); 
      }
      return this.swank;
   }

   /**
    * Put string to console.
    * 
    * @param str
    */
   public void out(final String str) {
      final MessageConsole myConsole = getConsole();
      final MessageConsoleStream out = myConsole.newMessageStream();
      out.println(str);
   }

   /**
    * This method is called upon plug-in activation
    */
   @Override
   public void start(final BundleContext context) throws Exception {
      super.start(context);
      this.cm = new ColorManager(this);

      try {
         final Properties props = new Properties();

         final InputStream in = LispPlugin.class.getResourceAsStream("/cusp.properties"); //$NON-NLS-1$
         props.load(in);
         in.close();

         LispPlugin.CUSP_VERSION = props.getProperty("cusp.version"); //$NON-NLS-1$
         LispPlugin.RELEASE_DATE = props.getProperty("cusp.release_date"); //$NON-NLS-1$

         // startSwank(); // FIXME: do this with launcher rather on startup
      }
      catch (final Exception e) {
         e.printStackTrace();
         // to show the error with the stack trace in error log view
         final IStatus errorStatus = new Status(IStatus.ERROR,
                                                "LispPlugin",
                                                0,
                                                e.getMessage(),
                                                e);
         getLog().log(errorStatus);
      }
   }

   public boolean startSwank() {
      if ((this.swank == null) || !this.swank.isConnected()) {
         this.swank = new SwankInterface();
      }
      else { // disconnect if already running and connect again.
         this.swank.reconnect();
      }
      return ((this.swank != null) && this.swank.isConnected());
   }

   public boolean startSwank(final String flavor, final String command) {
      if ((this.swank == null) || !this.swank.isConnected()) {
         this.swank = new SwankInterface(flavor, command);
      }
      else { // disconnect if already running and connect again.
         this.swank.reconnect();
      }
      return ((this.swank != null) && this.swank.isConnected());
   }

   /**
    * This method is called when the plug-in is stopped
    */
   @Override
   public void stop(final BundleContext context) throws Exception {
      this.cm.dispose();
      if ((getSwank() != null) && getSwank().isConnected()) {
         this.swank.disconnect();
      }
      LispPlugin.plugin = null;
      super.stop(context);
   }

   /**
    * @param msg
    *           prints message to Repl's status bar (if repl is available)
    */
   public void updateReplStatusLine(final String msg) {
      final IWorkbench workbench = PlatformUI.getWorkbench();
      final IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
      final IWorkbenchPage activePage = window.getActivePage();
      IStatusLineManager statusLineManager = null;
      if (activePage != null) {
         final IWorkbenchPart replPart = activePage.findView(ReplView.ID);
         if (replPart instanceof ReplView) {
            statusLineManager = ((IViewPart) replPart).getViewSite()
                                                      .getActionBars()
                                                      .getStatusLineManager();
            statusLineManager.setMessage(msg);
         }
      }
   }

   /**
    * @param msg
    *           prints message to Repl's status bar (if repl is available)
    */
   public void welcomeMessage(final String lispVersion, final String pkg) {
      final IWorkbench workbench = PlatformUI.getWorkbench();
      final IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
      final IWorkbenchPage activePage = window.getActivePage();
      IStatusLineManager statusLineManager = null;
      if (activePage != null) {
         final IWorkbenchPart replPart = activePage.findView(ReplView.ID);
         if (replPart instanceof ReplView) {
            statusLineManager = ((IViewPart) replPart).getViewSite()
                                                      .getActionBars()
                                                      .getStatusLineManager();
            statusLineManager.setMessage(LispPlugin.makeStatusMsg(lispVersion,
                                                                  pkg));
            ((ReplView) replPart).appendText("You are running " + lispVersion +
                     " via Cusp " + LispPlugin.getVersion());
         }
      }
   }

   private MessageConsole getConsole() {
      final ConsolePlugin plugin = ConsolePlugin.getDefault();
      final IConsoleManager conMan = plugin.getConsoleManager();
      final IConsole[] existing = conMan.getConsoles();
      for (final IConsole element : existing) {
         if (LispPlugin.CONSOLE_NAME.equals(element.getName())) {
            return (MessageConsole) element;
         }
      }
      // no console found, so create a new one
      final MessageConsole myConsole = new MessageConsole(LispPlugin.CONSOLE_NAME,
                                                          null);
      conMan.addConsoles(new IConsole[] { myConsole });
      return myConsole;
   }
}
