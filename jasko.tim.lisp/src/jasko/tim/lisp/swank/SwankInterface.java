package jasko.tim.lisp.swank;

import jasko.tim.lisp.LispPlugin;
import jasko.tim.lisp.builder.LispBuilder;
import jasko.tim.lisp.editors.actions.BreakpointAction;
import jasko.tim.lisp.editors.actions.WatchAction;
import jasko.tim.lisp.preferences.PreferenceConstants;
import jasko.tim.lisp.util.LispUtil;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;

/*
 * C:\sbcl\bin\sbcl.exe --load "C:/slime/swank-loader.lisp" --eval
 * "(swank:create-swank-server 4005 nil)"
 */

/**
 * The core guts of the plugin. All traffic to and from our lisp implementation
 * goes through here. Cusp operates off of the swank server that comes with
 * slime. Thus, to your lisp implementation, it's really no different from
 * slime. Swank makes our lives easier, as we don't have to worry about
 * implementing all of that stuff that the smart Swank developers did for us (in
 * a cross-implementation fashion, even). Now, a lot of this could probably be
 * cleaned up, and wisened up by somebody more familiar with Slime/Swank
 * development. I have learned the protocol almost entirely through packet
 * sniffing. My methods seem to work, but I could be rather naive in some
 * places. It should also be noted that the Swank developers are not above
 * changing the protocol for no good reason. Have fun, maintainers!
 * 
 * Rough overview: Requests/commands sent to Lisp are associated with a
 * SwankRunnable subclass, whose run() method processes the result once it comes
 * in. Pass <code>null</code> if you don't care about the result. Through here,
 * we also register various listeners for the various events that Lisp might
 * invoke (the debugger, for example). Probably 90% of these are subscribed to
 * by the Repl.
 * 
 * TODO: -Indentation is very dumb right now, and needs to be made smarter.
 * 
 * 
 * @see SwankRunnable
 * @author Tim Jasko
 */
public class SwankInterface {

   // Internal abstract class for filtering strings collected by
   // DisplayListenerThread.
   // The primary difference between this and a SwankRunnable is that there is a
   // deterministic guarantee that the filter will be run when the next display
   // line
   // is collected.
   private abstract class DisplayFilter {
      public abstract void filter(String str);
   } // class

   private class DisplayListenerThread extends Thread {
      public boolean                    running       = true;
      private StringBuffer              acc;
      // Number of accumulated results before syncing with listeners:
      private final int                 ACCUM_RESULTS = 4;
      private final boolean             echo;
      // Display listener filters: these are run immediately and separately from
      // Eclipse
      private final List<DisplayFilter> filters;
      private final BufferedReader      in;
      // Max size of characters to read from input stream, if available
      // N.B. need to keep this small enough so that stdOut/stdErr interleave in
      // an
      // intelligible manner (i.e. errors are correlated to the previous
      // output.)
      private final int                 MAX_READ      = 8;

      public DisplayListenerThread(final InputStream stream,
                                   final boolean echo_flag) {
         super("Secondary Swank Listener");
         this.in = new BufferedReader(new InputStreamReader(stream));
         this.running = true;
         this.acc = new StringBuffer();
         this.echo = echo_flag;
         this.filters = Collections.synchronizedList(new ArrayList<DisplayFilter>(1));
      }

      public void addFilter(final DisplayFilter filter) {
         synchronized (this.filters) {
            this.filters.add(filter);
         }
      }

      public String fetchText() {
         synchronized (this.acc) {
            final String ret = this.acc.toString();
            this.acc = new StringBuffer();
            return ret;
         }
      }

      public void removeFilter(final DisplayFilter filter) {
         synchronized (this.filters) {
            this.filters.remove(filter);
         }
      }

      @Override
      public void run() {
         final char[] cbuf = new char[this.MAX_READ];

         while (this.running) {
            try {
               LispNode result = new LispNode();
               int lines = 0;
               int nread = this.in.read(cbuf, 0, 1);
               if (nread < 0) {
                  System.out.println("Display input pipe closed.");
                  return;
               }
               // If we can read one character, we can potentially read more...
               if (this.in.ready()) {
                  final int naddl = this.in.read(cbuf, 1, this.MAX_READ - 1);
                  if (naddl > 0) {
                     nread += naddl;
                  }
                  // N.B., if naddl < 0, we'll catch that next iteration
               }

               synchronized (this.acc) {
                  this.acc.append(cbuf, 0, nread);
                  for (int nl = this.acc.indexOf("\n"); (nl >= 0) &&
                           (this.acc.length() > 0); nl = this.acc.indexOf("\n")) {
                     final String curr = this.acc.substring(0, nl);
                     this.acc = this.acc.delete(0, nl + 1);
                     if (this.echo) {
                        result.params.add(new LispNode(":write-string"));
                        result.params.add(new LispNode(curr));
                        if (lines >= this.ACCUM_RESULTS) {
                           signalListeners(SwankInterface.this.displayListeners,
                                           result);
                           lines = 0;
                           result = new LispNode();
                        }
                        else {
                           lines += 1;
                        }
                     }
                     System.out.print("]");
                     System.out.println(curr);
                     runFilters(curr);
                     if (curr.toLowerCase()
                             .contains(SwankInterface.this.implementation.fatalErrorString())) {
                        disconnect();
                     }
                  }
               }
            }
            catch (final IOException e) {
               e.printStackTrace();
            }
         }
      }

      private void runFilters(final String str) {
         synchronized (this.filters) {
            for (final DisplayFilter f : this.filters) {
               f.filter(str);
            }
         }
      }

   } // class

   private class ListenerThread extends Thread {
      private abstract class ListenerDispatch {
         public abstract void func(LispNode node);
      }

      public boolean                                    running  = true;
      private final Hashtable<String, ListenerDispatch> dispatch = new Hashtable<String, ListenerDispatch>();
      private BufferedReader                            in;

      public ListenerThread(final InputStream stream) {
         super("Swank Listener");

         try {
            this.in = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
         }
         catch (final UnsupportedEncodingException e) {
            System.out.println("Could not load UTF-8 character set -- something seriously wrong....");
            e.printStackTrace();
            throw new IllegalStateException("Could not initialize swank listener -- UTF-8 character set not available.",
                                            e);
         }

         // You would think that there's an easier way to fill the Hashtable,
         // but... put the special case handler actions in the dispatch hash
         // table:
         final ListenerDispatch default_dispatch = new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalResponse(node);
            }
         };

         this.dispatch.put(":ping", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               sendPong(node.get(1).value, node.get(2).value);
            }
         });
         this.dispatch.put(":debug-activate", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.debugListeners, node);
            }
         });
         this.dispatch.put(":debug", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.debugInfoListeners, node);
            }
         });
         this.dispatch.put(":debug-return", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.debugReturnListeners, node);
            }
         });
         this.dispatch.put(":read-string", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.readListeners, node);
            }
         });
         this.dispatch.put(":presentation-start", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               final String node1_value = node.get(1).value;
               for (final SwankRunnable r : SwankInterface.this.displayListeners) {
                  ((SwankDisplayRunnable) r).startPresentation(node1_value);
               }
            }
         });
         this.dispatch.put(":presentation-end", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               for (final SwankRunnable r : SwankInterface.this.displayListeners) {
                  ((SwankDisplayRunnable) r).endPresentation();
               }
            }
         });
         this.dispatch.put(":write-string", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.displayListeners, node);
            }
         });
         this.dispatch.put(":indentation-update", new ListenerDispatch() {
            @Override
            public void func(final LispNode node) {
               signalListeners(SwankInterface.this.indentationListeners, node);
            }
         });
         this.dispatch.put(":return", default_dispatch);
         this.dispatch.put(":reader-error", default_dispatch);
         this.dispatch.put(":new-features", default_dispatch);
         this.dispatch.put(":new-package", default_dispatch);
      }

      @Override
      public void run() {
         // Each message is prefixed by a hex length, so create a permanent
         // buffer to receive it:
         final char[] lbuf = new char[6];
         // rbuf is the raw reply buffer, initially sized to something
         // reasonable
         // Note: this is reallocated to a larger size as needed to keep the
         // amount of object allocation down to a reasonable minimum.
         char[] rbuf = new char[256];
         // reply is the String version of rbuf.
         String reply = new String();
         int nread = 0;

         while (this.running) {
            try {
               if (true) {
                  nread = this.in.read(lbuf, 0, lbuf.length);
                  if (nread < 0) {
                     System.out.println("Connection closed");
                     signalListeners(SwankInterface.this.disconnectListeners,
                                     new LispNode());
                     return;
                  }

                  final String lstring = String.copyValueOf(lbuf,
                                                            0,
                                                            lbuf.length);
                  final int length = Integer.parseInt(lstring, 16);

                  if (length > rbuf.length) {
                     rbuf = new char[length];
                  }

                  // Because we're sitting on a network socket, there's a good
                  // possibility
                  // that the reply exceeds the host's localhost MTU, so we need
                  // to be aggressive
                  // in collecting the entire reply (and there will only be one
                  // MTU "in flight"
                  // at any given point in time.)
                  nread = 0;
                  do {
                     final int n = this.in.read(rbuf, nread, length - nread);
                     if (n < 0) {
                        System.out.println("Connection closed");
                        signalListeners(SwankInterface.this.disconnectListeners,
                                        new LispNode());
                        return;
                     }
                     nread += n;
                  } while (nread < length);

                  // Reify the string...
                  reply = String.copyValueOf(rbuf, 0, nread);
                  System.out.println("<--" + reply);
                  System.out.flush();
                  if (reply.contains("open-dedicated-output-stream")) {
                     final StringTokenizer tokens = new StringTokenizer(reply,
                                                                        " )");
                     while (tokens.hasMoreTokens()) {
                        try {
                           final String token = tokens.nextToken();
                           System.out.println(token);
                           final int tmp = Integer.parseInt(token);
                           System.out.println("secondary:" + tmp);
                           SwankInterface.this.secondary = new Socket("localhost",
                                                                      tmp);
                           SwankInterface.this.displayListener = new DisplayListenerThread(SwankInterface.this.secondary.getInputStream(),
                                                                                           true);
                           SwankInterface.this.displayListener.start();
                        }
                        catch (final Exception e) {
                           // e.printStackTrace();
                        } // catch
                     } // while
                  }
                  else {
                     // System.out.println("parsing");
                     handle(LispParser.parse(reply.substring(1)));
                  }
               } // if

            }
            catch (final IOException e) {
               e.printStackTrace();
               signalListeners(SwankInterface.this.disconnectListeners,
                               new LispNode());
               return;
            }
         } // while
         System.out.println("Done listening");
      }

      private void handle(final LispNode node) {
         try {
            final ListenerDispatch l = this.dispatch.get(node.car().value);
            if (l != null) {
               l.func(node);
            }
            else {
               System.err.println("** unhandled node type: " + node.toString());
            }
         }
         catch (final Exception e) {
            e.printStackTrace();
         }
      }

   } // class

   private class SwankStartFilter extends DisplayFilter {
      private boolean got_start_string;

      public SwankStartFilter() {
         this.got_start_string = false;
      }

      @Override
      public void filter(final String str) {
         if (str.contains("Swank started")) {
            synchronized (this) {
               this.got_start_string = true;
               notifyAll();
            }
         }
      }

      public synchronized boolean getStartStringFlag() {
         return this.got_start_string;
      }
   } // class

   private class SyncCallback {
      public LispNode result = new LispNode();
   }

   /** Port of the Swank server */
   private static Integer                         port             = 4004;
   public Hashtable<String, String>               fletIndents;
   public Hashtable<String, String>               handlerCaseIndents;
   public LispImplementation                      implementation;

   public Hashtable<String, Integer>              indents;
   public Hashtable<String, String>               specialIndents;
   DataOutputStream                               commandInterface = null;
   Process                                        lispEngine;

   /** Number of tries we attempt to connect to Lisp */
   private final int                              connect_retries  = 3;

   /** Holds whether we are connected to Swank. */
   private boolean                                connected        = false;

   private String                                 currPackage      = "COMMON-LISP-USER";
   /**
    * Listeners to be given debug info, usually right before a :debug-activate.
    */
   private final List<SwankRunnable>              debugInfoListeners;

   /**
    * Listeners to be notified when the debugger is activated.
    */
   private final List<SwankRunnable>              debugListeners;
   /**
    * Listeners to be notified when the debugger should be dismissed
    */
   private final List<SwankRunnable>              debugReturnListeners;

   /**
    * For those who want to be informed of the death of our Lisp.
    */
   private final List<SwankRunnable>              disconnectListeners;
   private DisplayListenerThread                  displayListener;

   /**
    * Listeners to be notified of anything to be output.
    */
   private final List<SwankRunnable>              displayListeners;

   private Socket                                 echoSocket       = null;

   /**
    * Anybody who wants to know that indentation has been updated.
    */
   private final List<SwankRunnable>              indentationListeners;

   /**
    * Holds all outstanding jobs that we're waiting for Lisp to finish
    * processing, except those that are being executed synchronously.
    */
   private final Hashtable<String, SwankRunnable> jobs             = new Hashtable<String, SwankRunnable>();

   private String                                 lastTestPackage  = "nil";                                 // FIXME:
                                                                                                             // this
                                                                                                             // should
                                                                                                             // be
                                                                                                             // in
                                                                                                             // test
                                                                                                             // view

   private String                                 lispCommand      = "";

   private String                                 lispFlavor       = "";

   private String                                 lispVersion      = "(NO CL IMPLEMENTATION)";

   private ListenerThread                         listener;

   private ProcessBuilder                         m_pb;

   // SK: note, MANAGE_PACKAGES and USE_UNIT_TEST preferences should be
   // accessed only through SwankInterface.getManagePackages and
   // swankInterface.getUnitTest - because their value only have importance
   // when lisp starts
   private boolean                                managePackages   = false;
   private int                                    messageNum       = 1;
   private DataOutputStream                       out              = null;
   /**
    * Listeners to be notified whenever Lisp is trying to read something from
    * the user.
    */
   private final List<SwankRunnable>              readListeners;

   private Socket                                 secondary        = null;

   private DisplayListenerThread                  stdErr;

   private DisplayListenerThread                  stdOut;

   /**
    * Holds jobs that are being executed synchronously. We handle them slightly
    * differently.
    */
   private final Hashtable<String, SyncCallback>  syncJobs         = new Hashtable<String, SyncCallback>();

   private boolean                                useUnitTest      = false;

   public SwankInterface() {
      this.debugListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.debugInfoListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.debugReturnListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.displayListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.readListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.disconnectListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.indentationListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));

      initIndents();
      connect();
   }

   public SwankInterface(final String flavor, final String command) {
      this.debugListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.debugInfoListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.debugReturnListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.displayListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.readListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.disconnectListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));
      this.indentationListeners = Collections.synchronizedList(new ArrayList<SwankRunnable>(1));

      initIndents();
      connect(flavor, command);
   }

   public void addDebugInfoListener(final SwankRunnable callBack) {
      synchronized (this.debugInfoListeners) {
         this.debugInfoListeners.add(callBack);
      }
   }

   public void addDebugListener(final SwankRunnable callBack) {
      synchronized (this.debugListeners) {
         this.debugListeners.add(callBack);
      }
   }

   public void addDebugReturnListener(final SwankRunnable callBack) {
      synchronized (this.debugReturnListeners) {
         this.debugReturnListeners.add(callBack);
      }
   }

   public void addDisconnectCallback(final SwankRunnable callBack) {
      synchronized (this.disconnectListeners) {
         this.disconnectListeners.add(callBack);
      }
   }

   public void addDisplayCallback(final SwankRunnable callBack) {
      synchronized (this.displayListeners) {
         this.displayListeners.add(callBack);
      }
   }

   public void addIndentationListener(final SwankRunnable callBack) {
      synchronized (this.indentationListeners) {
         this.indentationListeners.add(callBack);
      }
   }

   public void addReadListener(final SwankRunnable callBack) {
      synchronized (this.readListeners) {
         this.readListeners.add(callBack);
      }
   }

   public void compileAndLoadAsd(final IFile file, final boolean switchToPackage) {
      if (file == null) {
         return;
      }
      final String fext = file.getFileExtension();
      if ((fext == null) || !fext.equalsIgnoreCase("asd")) {
         return;
      }
      final String fullpath = file.getLocation().toString();
      final String fname = file.getName();
      final String path = fullpath.substring(0,
                                             fullpath.length() - fname.length());
      final String name = fname.substring(0, fname.length() - ".asd".length());
      if ((fext != null) && fext.equalsIgnoreCase("asd")) {
         registerLibPath(path);
         registerCallback(new LispBuilder.CompileListener(true,
                                                          fullpath,
                                                          switchToPackage));
         final String msg = "(swank:operate-on-system-for-emacs \"" + name +
                  "\" \"LOAD-OP\")";
         emacsRex(msg);
      }

   }

   /**
    * Connects to the swank server.
    * 
    * @return whether connecting was successful
    */
   public boolean connect() {
      this.connected = false;
      this.currPackage = "COMMON-LISP-USER";
      // IPreferenceStore store = LispPlugin.getDefault().getPreferenceStore();

      synchronized (SwankInterface.port) {
         ++SwankInterface.port;
         this.implementation = null;

         // First attempt to use preferences to identify lisp implementation
         final IPreferenceStore prefStore = LispPlugin.getDefault()
                                                      .getPreferenceStore();
         if (prefStore.getBoolean(PreferenceConstants.USE_SITEWIDE_LISP)) {
            this.implementation = SiteWideImplementation.findImplementation();
         }
         else
            if (prefStore.getBoolean(PreferenceConstants.USE_REMOTE_LISP)) {
               this.implementation = RemoteImplementation.findImplementation();
            }

         // As a fallback, if the above failed, find an implementation
         // and start a lisp process trying sbcl and allegro in default
         // locations:
         if (this.implementation == null) {
            this.implementation = SBCLImplementation.findImplementation();
         }
         if (this.implementation == null) {
            this.implementation = AllegroImplementation.findImplementation();
         }

         final String pluginDir = LispPlugin.getDefault().getPluginPath();
         final String slimePath = pluginDir + "slime/swank-loader.lisp";
         if (this.implementation != null) {
            try {
               this.lispEngine = this.implementation.start(slimePath,
                                                           SwankInterface.port);
            }
            catch (final IOException e3) {
               e3.printStackTrace();
               return false;
            }
         }
         else {
            // always try to load sbcl as second fallback
            try {
               this.m_pb = new ProcessBuilder(new String[] { "sbcl", "--load",
                        slimePath });
               this.lispEngine = this.m_pb.start();
            }
            catch (final IOException e) {
               return false;
            }
         }

         int retries = this.connect_retries;
         do {
            if (connectStreams(slimePath)) {
               try {
                  this.echoSocket = new Socket("localhost", SwankInterface.port);
                  this.out = new DataOutputStream(this.echoSocket.getOutputStream());
                  this.listener = new ListenerThread(this.echoSocket.getInputStream());
                  this.listener.start();
                  break;
               }
               catch (final UnknownHostException e) {
                  return false;
               }
               catch (final IOException e) {
                  try {
                     final int val = this.lispEngine.exitValue();
                     System.out.println("exit: " + val);

                     this.lispEngine = this.implementation.startHarder(slimePath,
                                                                       SwankInterface.port);
                     --retries; // try to connect again
                  }
                  catch (final IOException e2) {
                     e.printStackTrace();
                     return false;
                  }
               }
            }
         } while (retries >= 0);
      } // synchronized

      // sendRaw("(:emacs-rex (swank:connection-info) nil t 1)");
      if ((this.echoSocket != null) && this.echoSocket.isConnected()) {
         this.connected = true;
      }
      else {
         this.connected = false;
      }
      runAfterLispStart();
      return this.connected;
   }

   // FIXME: right now implemented only for sbcl. In this case command is a
   // filepath to sbcl
   // FIXME: STOP THE COPYPASTA.
   public boolean connect(final String flavor, final String command) {
      this.connected = false;
      this.currPackage = "COMMON-LISP-USER";
      // IPreferenceStore store = LispPlugin.getDefault().getPreferenceStore();

      if ( !flavor.equalsIgnoreCase("sbcl")) {
         return false;
      }

      this.lispFlavor = flavor;
      this.lispCommand = command;

      synchronized (SwankInterface.port) {
         ++SwankInterface.port;
         this.implementation = null;

         this.implementation = SBCLImplementation.findImplementation(command);

         final String pluginDir = LispPlugin.getDefault().getPluginPath();
         final String slimePath = pluginDir + "slime/swank-loader.lisp";
         if (this.implementation != null) {
            try {
               this.lispEngine = this.implementation.start(slimePath,
                                                           SwankInterface.port);
            }
            catch (final IOException e3) {
               e3.printStackTrace();
               return false;
            }
         }
         else {
            try {
               this.m_pb = new ProcessBuilder(new String[] { "sbcl", "--load",
                        slimePath });
               this.lispEngine = this.m_pb.start();
            }
            catch (final IOException e) {
               return false;
            }
         }

         int retries = 1;
         do {
            if (connectStreams(slimePath)) {
               try {
                  this.echoSocket = new Socket("localhost", SwankInterface.port);
                  this.out = new DataOutputStream(this.echoSocket.getOutputStream());
                  this.listener = new ListenerThread(this.echoSocket.getInputStream());
                  this.listener.start();
                  break;
               }
               catch (final UnknownHostException e) {
                  return false;
               }
               catch (final IOException e) {
                  try {
                     final int val = this.lispEngine.exitValue();
                     System.out.println("exit: " + val);

                     this.lispEngine = this.implementation.startHarder(slimePath,
                                                                       SwankInterface.port);
                     --retries; // try to connect again
                  }
                  catch (final IOException e2) {
                     e.printStackTrace();
                     return false;
                  }
               }
            }
         } while (retries >= 0);
      } // synchronized

      // sendRaw("(:emacs-rex (swank:connection-info) nil t 1)");
      if ((this.echoSocket != null) && this.echoSocket.isConnected()) {
         this.connected = true;
      }
      else {
         this.connected = false;
      }
      runAfterLispStart();
      return this.connected;
   }

   /***
    * Signals listeners that a disconnection event is occurring and instructs Swank to quit lisp.
    */
   public void disconnect() {
      this.connected = false;
      signalListeners(this.disconnectListeners, new LispNode());
      System.out.println("*disconnect");
      if (System.getProperty("os.name").toLowerCase().contains("windows")) {
         if (this.lispEngine != null) {
            this.lispEngine.destroy();
            this.lispEngine = null;
         }
      }

      try {
         this.commandInterface.writeBytes("(swank:quit-lisp)\n");
         this.commandInterface.flush();
      }
      catch (final IOException e) {
         e.printStackTrace();
      }

      try {
         if (this.displayListener != null) {
            this.displayListener.running = false;
         }
         if (this.stdOut != null) {
            this.stdOut.running = false;
         }
         if (this.stdErr != null) {
            this.stdErr.running = false;
         }

         if (this.listener != null) {
            this.listener.running = false;
         }
         else {
            System.err.println("lisp instance wasn't running.");
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
      }

      try {
         if (this.lispEngine != null) {
            this.currPackage = "COMMON-LISP-USER";
            // sendEval("(quit)", null);

            // lispEngine.destroy();
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
      }
   }

   public synchronized boolean emacsRex(final String message) {
      return emacsRexWithThread(message, ":repl-thread");
   }

   public synchronized boolean emacsRex(final String message, final String pkg) {
      return emacsRexWithThread(message, pkg, ":repl-thread");
   }

   public synchronized boolean emacsRexWithThread(final String message,
                                                  final String threadId) {
      final String msg = "(:emacs-rex " + message + " nil " + threadId + " " +
               this.messageNum + ")";

      return sendRaw(msg);
   }

   public synchronized boolean emacsRexWithThread(final String message,
                                                  final String pkg,
                                                  final String threadId) {
      final String msg = "(:emacs-rex " + message + " " +
               LispUtil.cleanPackage(pkg) + " " + threadId + " " +
               this.messageNum + ")";

      return sendRaw(msg);
   }

   public String fetchDisplayText() {
      return this.displayListener.fetchText();
   }

   public synchronized String getArglist(final String function,
                                         final long timeout) {
      return getArglist(function, timeout, this.currPackage);
   }

   public synchronized String getArglist(final String function,
                                         final long timeout,
                                         final String currPackage) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final String msg = "(swank:operator-arglist \"" +
               LispUtil.formatCode(function) + "\" " +
               LispUtil.cleanPackage(currPackage) + " )";

      try {
         synchronized (callBack) {
            if (emacsRex(msg, currPackage)) {

               callBack.wait(timeout);
               return callBack.result.cadr().cadr().value;
            }
            else {
               return "";
            }
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
         return "";
      }
   }

   public synchronized ArrayList<String> getAvailablePackages(final long timeout) {
      final SyncCallback callback = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callback);

      final java.util.ArrayList<String> packageNames = new java.util.ArrayList<String>();

      try {
         synchronized (callback) {
            if (emacsRex("(swank:list-all-package-names t)")) {
               callback.wait(timeout);
               final LispNode packages = callback.result.getf(":return")
                                                        .getf(":ok");
               for (final LispNode p : packages.params) {
                  packageNames.add(p.value);
               }
            }
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
      }

      return packageNames;
   }

   public synchronized String[] getCompletions(final String start,
                                               final long timeout) {
      return getCompletions(start, this.currPackage, timeout);
   }

   public synchronized String[] getCompletions(final String start,
                                               final String pkg,
                                               final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final IPreferenceStore prefs = LispPlugin.getDefault()
                                               .getPreferenceStore();
      String msg = "";
      int nlim = 0;

      final boolean usefuzzy = prefs.getBoolean(PreferenceConstants.AUTO_FUZZY_COMPLETIONS);
      if (usefuzzy) {
         final String tlimit = "50";
         String nlimit = prefs.getString(PreferenceConstants.AUTO_COMPLETIONS_NLIMIT);
         if ( !nlimit.matches("\\d+")) {
            nlimit = "0";
         }
         else {
            nlim = prefs.getInt(PreferenceConstants.AUTO_COMPLETIONS_NLIMIT);
         }
         msg = "(swank:fuzzy-completions \"" + start + "\" " +
                  LispUtil.cleanPackage(pkg) + " :limit " + nlimit +
                  " :time-limit-in-msec " + tlimit + ")";
      }
      else {
         msg = "(swank:simple-completions \"" + start + "\" " +
                  LispUtil.cleanPackage(pkg) + ")";
      }

      try {
         synchronized (callBack) {
            if (emacsRex(msg)) {
               callBack.wait(timeout);
               LispNode results = callBack.result.cadr().cadr();
               // for some reasons sometimes it is in car() and at other times
               // just at results
               if (results.cadr().value.equalsIgnoreCase("nil")) {
                  results = results.car();
               }
               final String[] ret = new String[results.params.size()];
               if (usefuzzy) {
                  int nn = nlim;
                  if (nn == 0) {
                     nn = results.params.size();
                  }
                  else {
                     nn = Math.min(results.params.size(), nn);
                  }
                  for (int i = 0; i < nn; ++i) {
                     ret[i] = results.get(i).car().value;
                  } // for
               }
               else {
                  for (int i = 0; i < results.params.size(); ++i) {
                     ret[i] = results.get(i).value;
                  } // for
               }
               return ret;
            }
            else {
               return null;
            }
         } // sync
      }
      catch (final Exception e) {
         e.printStackTrace();
         return null;
      } // catch
   }

   /**
    * 
    * @param start
    *           of the string
    * @param pkg
    *           current package (if null, then swank.currPackage)
    * @param timeout
    *           timeout for swank call
    * @param n
    *           number of completions to get: 0 if no limit (limit works only
    *           for fuzzy completions)
    * @return string[2][n] : string[0] - completions, string[1] - arglists +
    *         docs
    */
   public synchronized String[][] getCompletionsAndDocs(final String start,
                                                        final String pkg,
                                                        final long timeout,
                                                        final int n) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final IPreferenceStore prefs = LispPlugin.getDefault()
                                               .getPreferenceStore();
      final boolean usefuzzy = prefs.getBoolean(PreferenceConstants.AUTO_FUZZY_COMPLETIONS);

      String msg = "";
      String usepkg = this.currPackage;

      if (usefuzzy) {
         msg = "(let ((lst (mapcar #'first (let ((x (swank:fuzzy-completions ";
      }
      else {
         msg = "(let ((lst (first (swank:simple-completions ";
      }
      msg += "\"" + start + "\" ";
      if (pkg == null) {
         msg += LispUtil.cleanPackage(this.currPackage) + " ";
      }
      else {
         msg += LispUtil.cleanPackage(pkg) + " ";
         usepkg = pkg;
      }
      if (usefuzzy) {
         msg += " :time-limit-in-msec " + timeout;
         if (n > 0) {
            msg += " :limit " + n;
         }
         msg += "))) (if (listp (first (first x))) (first x) x)";
      }
      msg += "))))";
      msg += "(list lst (mapcar #'(lambda (y) (swank:operator-arglist y " +
               LispUtil.cleanPackage(pkg) + ")) lst)" +
               " (mapcar #'(lambda (z) (swank:documentation-symbol z)) lst)))";
      final LispNode resNode = LispParser.parse(sendEvalAndGrab(msg,
                                                                usepkg,
                                                                timeout));
      final LispNode compl = resNode.car().get(0);
      final LispNode args = resNode.car().get(1);
      final LispNode docs = resNode.car().get(2);
      final int nn = compl.params.size();
      if (0 == nn) {
         return null;
      }

      final String[][] res = new String[2][nn];

      for (int i = 0; i < nn; ++i) {
         String info = args.get(i).value;
         if (info.equalsIgnoreCase("nil") || info.contains("not available")) {
            info = "";
         }
         final String docString = docs.get(i).value;
         if ( !docString.equals("") && !docString.equalsIgnoreCase("nil")) {
            final String[] lines = docString.split("\n");
            final int maxlines = prefs.getInt(PreferenceConstants.MAX_HINT_LINES);
            if ((maxlines > 0) && (lines.length > maxlines)) {
               for (int j = 0; j < maxlines; ++j) {
                  info += "\n" + lines[j];
               }
               info += "...";
            }
            else {
               info += "\n" + docString;
            }
         }
         res[0][i] = compl.get(i).value;
         res[1][i] = info;
      }
      return res;

   }

   public String getCurrPackage() {
      return this.currPackage;
   }

   public synchronized String getDocumentation(final String function,
                                               final long timeout) {
      return getDocumentation(function, this.currPackage, timeout);
   }

   public synchronized String getDocumentation(final String function,
                                               final String pkg,
                                               final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final String msg = "(swank:documentation-symbol \"" +
               LispUtil.formatCode(function) + "\")";

      try {
         synchronized (callBack) {
            if (emacsRex(msg, pkg)) {

               callBack.wait(timeout);
               final String result = callBack.result.getf(":return")
                                                    .getf(":ok").value;
               if (result.equalsIgnoreCase("nil")) {
                  return "";
               }
               else {
                  return result;
               }
            }
            else {
               return "";
            }
         }
      }
      catch (final InterruptedException e) {
         e.printStackTrace();
         return "";
      }
   }

   public synchronized LispNode getInstalledPackages(final long timeout) {
      if (this.managePackages) {

         final SyncCallback callback = new SyncCallback();
         ++this.messageNum;
         this.syncJobs.put(new Integer(this.messageNum).toString(), callback);

         final String res = sendEvalAndGrab("(get-installed-packages)",
                                            "com.gigamonkeys.asdf-extensions",
                                            timeout);
         final LispNode resnode = LispParser.parse(res);
         return resnode;
      }
      else {
         return null;
      }
   }

   public String getlastTestPackage() {
      return this.lastTestPackage;
   }

   public String getLispVersion() {
      return this.lispVersion;
   }

   public synchronized String getMakeInstanceArglist(final String className,
                                                     final long timeout) {
      return getMakeInstanceArglist(className, this.currPackage, timeout);
   }

   public synchronized String getMakeInstanceArglist(final String className,
                                                     final String pkg,
                                                     final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);
      // (swank:arglist-for-echo-area (quote ((:make-instance "some-class"
      // "make-instance"))))
      final String msg = "(swank:arglist-for-echo-area (quote ((\"MAKE-INSTANCE\" \"'" +
               LispUtil.formatCode(className) + "\"))))";

      try {
         synchronized (callBack) {
            if (emacsRex(msg, pkg)) {

               callBack.wait(timeout);
               return callBack.result.getf(":return").getf(":ok").value;
            }
            else {
               return "";
            }
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
         return "";
      }
   }

   public boolean getManagePackages() {
      return this.managePackages;
   }

   public String getPackage() {
      return this.currPackage;
   }

   public synchronized ArrayList<String> getPackagesWithTests(final long timeout) {
      if (this.useUnitTest) {

         final SyncCallback callback = new SyncCallback();
         ++this.messageNum;
         this.syncJobs.put(new Integer(this.messageNum).toString(), callback);

         final java.util.ArrayList<String> packageNames = new java.util.ArrayList<String>();

         final String res = sendEvalAndGrab("(let ((res '())) (dolist (pkg (swank:list-all-package-names t) res)"
                                                     + "(if (lisp-unit:get-tests pkg) (push pkg res))))",
                                            "COMMON-LISP-USER",
                                            timeout);
         final LispNode resnode = LispParser.parse(res).get(0);
         for (final LispNode p : resnode.params) {
            packageNames.add(p.value);
         }
         return packageNames;
      }
      else {
         return null;
      }
   }

   public synchronized String getSpecialArglist(final String function,
                                                final String arg0,
                                                final long timeout) {
      return getSpecialArglist(function, arg0, this.currPackage, timeout);
   }

   public synchronized String getSpecialArglist(final String function,
                                                final String arg0,
                                                final String pkg,
                                                final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);
      // (swank:arglist-for-echo-area (quote ((:make-instance "some-class"
      // "make-instance"))))
      final String msg = "(swank:arglist-for-echo-area (quote ((\"" +
               LispUtil.formatCode(function) + "\" \"" +
               LispUtil.formatCode(arg0) + "\" ))))";

      try {
         synchronized (callBack) {
            if (emacsRex(msg, pkg)) {

               callBack.wait(timeout);
               return callBack.result.getf(":return").getf(":ok").value;
            }
            else {
               return "";
            }
         }
      }
      catch (final Exception e) {
         e.printStackTrace();
         return "";
      }
   }

   public boolean getUseUnitTest() {
      return this.useUnitTest;
   }

   // finds definitions in package pkg or global context
   public synchronized boolean haveDefinitions(final String symbol,
                                               final String pkg,
                                               final long timeout) {
      return (haveDefinitionsPkg(symbol, pkg, timeout) || haveDefinitionsPkg(symbol,
                                                                             "",
                                                                             timeout));
   }

   public boolean isConnected() {
      return this.connected;
   }

   public void reconnect() {
      disconnect();
      if (this.lispFlavor != "") {
         connect(this.lispFlavor, this.lispCommand);
      }
      else {
         connect();
      }
   }

   public synchronized void registerLibPath(final String path) {
      String code = path.replace("\\", "/");
      if ( !code.endsWith("/")) {
         code += "/";
      }
      code = "(com.gigamonkeys.asdf-extensions:register-source-directory \"" +
               code + "\")";
      sendEvalAndGrab(code, 1000);
   }

   public void removeDebugInfoListener(final SwankRunnable callBack) {
      synchronized (this.debugInfoListeners) {
         this.debugInfoListeners.remove(callBack);
      }
   }

   public void removeDebugListener(final SwankRunnable callBack) {
      synchronized (this.debugListeners) {
         this.debugListeners.remove(callBack);
      }
   }

   public void removeDebugReturnListener(final SwankRunnable callBack) {
      synchronized (this.debugReturnListeners) {
         this.debugReturnListeners.remove(callBack);
      }
   }

   public void removeDisconnectCallback(final SwankRunnable callBack) {
      synchronized (this.disconnectListeners) {
         this.disconnectListeners.remove(callBack);
      }
   }

   public void removeDisplayCallback(final SwankRunnable callBack) {
      synchronized (this.displayListeners) {
         this.displayListeners.remove(callBack);
      }
   }

   public void removeIndentationListener(final SwankRunnable callBack) {
      synchronized (this.indentationListeners) {
         this.indentationListeners.remove(callBack);
      }
   }

   public void removeReadListener(final SwankRunnable callBack) {
      synchronized (this.readListeners) {
         this.readListeners.remove(callBack);
      }
   }

   public void runAfterLispStart() {
      if (isConnected()) {
         final IPreferenceStore prefs = LispPlugin.getDefault()
                                                  .getPreferenceStore();
         this.managePackages = prefs.getBoolean(PreferenceConstants.MANAGE_PACKAGES);
         this.useUnitTest = prefs.getBoolean(PreferenceConstants.USE_UNIT_TEST);
         String strIni = prefs.getString(PreferenceConstants.LISP_INI);
         SwankRunnable sr = null;

         String startupCode = "(progn (swank:swank-require :swank-fuzzy)\n" +
                  "(swank:swank-require :swank-asdf)\n" +
                  "(swank:swank-require :swank-presentations)\n" +
                  "(swank:swank-require :swank-fancy-inspector)\n" +
                  "(swank:swank-require :swank-arglists)\n" +
                  "(swank:swank-require :swank-presentations)\n" +
                  BreakpointAction.macro + "\n" + WatchAction.macro + "\n";

         final String asdfext = LispPlugin.getDefault().getPluginPath() +
                  "lisp-extensions/asdf-extensions.lisp";
         startupCode += "(load \"" + asdfext + "\")\n";

         if (this.useUnitTest) {
            startupCode += "(load \"" +
                     LispPlugin.getDefault().getPluginPath() +
                     "lisp-extensions/lisp-unit.lisp" + "\")\n";
         }
         if (this.managePackages) {
            final String code = LispPlugin.getDefault()
                                          .getLibsPathRegisterCode();
            if ( !code.equals("")) {
               System.out.printf("asdf path: %s\n", asdfext);
               sr = new SwankRunnable() {
                  @Override
                  public void run() {
                     emacsRex(code, "COMMON-LISP-USER");
                  }
               };
            }
         }
         if (strIni != "") {
            strIni = strIni.replaceAll("\\\\", "/");
            startupCode += "(when (probe-file \"" + strIni + "\") (load \"" +
                     strIni + "\"))\n";
         }

         startupCode += "(format nil \"You are running ~a ~a via Cusp " +
                  LispPlugin.getVersion() +
                  "\" (lisp-implementation-type) (lisp-implementation-version))" +
                  ")";

         sendEval(startupCode, sr);
         // sendEval("(swank:fancy-inspector-init)", null);

         sendGetConnectionInfo(new SwankRunnable() {
            @Override
            public void run() {
               final LispNode impl = getReturn().getf(":lisp-implementation");
               setLispVersion(impl.getf(":name").value + " " +
                        impl.getf(":version").value);
            }
         });
      }
   }

   public synchronized void sendAbortDebug(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:sdbl-abort)";

      emacsRex(msg, getPackage());
   }

   public synchronized void sendApropos(String regex,
                                        final SwankRunnable callBack) {
      registerCallback(callBack);
      // Need to protect against these, as they send lisp into an endless loop.
      // Probably others do as well, but these I know about.
      if (regex.equals("+")) {
         regex = "\\+";
      }
      else
         if (regex.equals("*")) {
            regex = "\\*";
         }
      final String msg = "(swank:apropos-list-for-emacs \"" +
               LispUtil.formatCode(regex) + "\" t nil)";

      emacsRex(msg);
   }

   public synchronized void sendCompileFile(String filePath,
                                            final SwankRunnable callBack) {
      registerCallback(callBack);
      filePath = filePath.replace('\\', '/');
      filePath = this.implementation.translateLocalFilePath(filePath);
      final String msg = "(swank:compile-file-for-emacs \"" + filePath +
               "\" t)";

      emacsRex(msg);
   }

   public synchronized void sendCompileString(final String expr,
                                              final String file,
                                              String dir,
                                              final int offset,
                                              final String pckg,
                                              final SwankRunnable callBack) {
      registerCallback(callBack);
      System.out.println(file);
      System.out.println(dir);
      dir = this.implementation.translateLocalFilePath(dir);
      final String msg = "(swank:compile-string-for-emacs \"" +
               LispUtil.formatCode(expr) + "\" \"" +
               LispUtil.formatCode(dir + file) + "\" " + (offset + 1) + " \"" +
               LispUtil.formatCode(dir) + "\" 3)"; // 3 = debug level
      if (pckg.equalsIgnoreCase("nil")) {
         emacsRex(msg);
      }
      else {
         emacsRex(msg, pckg);
      }
   }

   // Stepping related

   public synchronized void sendContinueDebug(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:sdbl-continue)";

      emacsRex(msg);
   }

   // Inspection related

   public synchronized void sendDebug(final String commandNum,
                                      final String sldbLevel,
                                      final String threadId,
                                      final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:invoke-nth-restart-for-emacs " + sldbLevel +
               " " + commandNum + ")";

      emacsRexWithThread(msg, threadId);
   }

   public synchronized void sendDebugThread(final String threadNum,
                                            final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:debug-nth-thread " + threadNum + ")";

      emacsRex(msg);
   }

   public synchronized void sendDisassemble(final String symbol,
                                            final String pkg,
                                            final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:disassemble-symbol \"" + symbol + "\")";

      emacsRex(msg, pkg);
   }

   public synchronized void sendEval(String message,
                                     final SwankRunnable callBack) {
      registerCallback(callBack);
      message = message + "\n";
      final String msg = "(swank:listener-eval \"" +
               LispUtil.formatCode(message) + "\")";

      emacsRex(msg);
   }

   public synchronized String sendEvalAndGrab(final String message,
                                              final long timeout) {
      return sendEvalAndGrab(message, "nil", timeout);
   }

   // Debug related

   /* TODO: - make it with progress bar */
   public synchronized String sendEvalAndGrab(final String message,
                                              final String pkg,
                                              final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final String msg = "(swank:eval-and-grab-output \"" +
               LispUtil.formatCode(message) + "\")";

      try {
         synchronized (callBack) {
            if (emacsRex(msg, pkg)) {
               callBack.wait(timeout);
               final LispNode res = callBack.result.getf(":return").getf(":ok");
               if (res.params.size() > 0) {
                  return (res.params.get(1).value);
               }
               else {
                  return "";
               }
            }
            else {
               return "";
            }
         } // sync
      }
      catch (final Exception e) {
         e.printStackTrace();
         return "";
      } // catch
   }

   public synchronized void sendFindDefinitions(final String symbol,
                                                final String pkg,
                                                final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:find-definitions-for-emacs \"" +
               LispUtil.formatCode(symbol) + "\")";

      emacsRex(msg, pkg);
   }

   public synchronized void sendGetArglist(final String function,
                                           final String currPackage,
                                           final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:operator-arglist \"" +
               LispUtil.formatCode(function) + "\" " +
               LispUtil.cleanPackage(currPackage) + ")";

      emacsRex(msg, currPackage);
   }

   public synchronized void sendGetAvailablePackages(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:list-all-package-names t)";

      emacsRex(msg);
   }

   public synchronized void sendGetCallees(final String functionName,
                                           final String pkg,
                                           final SwankRunnable callBack) {
      registerCallback(callBack);
      emacsRex("(swank:xref (quote :callees) (quote \"" +
                        LispUtil.formatCode(functionName) + "\"))",
               pkg);
   }

   public synchronized void sendGetCallers(final String functionName,
                                           final String pkg,
                                           final SwankRunnable callBack) {
      registerCallback(callBack);
      emacsRex("(swank:xref (quote :callers) (quote \"" +
                        LispUtil.formatCode(functionName) + "\"))",
               pkg);
   }

   public synchronized void sendGetConnectionInfo(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:connection-info)";

      emacsRex(msg);
   }

   public synchronized void sendGetDocumentation(final String function,
                                                 final String pkg,
                                                 final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:documentation-symbol \"" +
               LispUtil.formatCode(function) + "\")";

      emacsRex(msg, pkg);
   }

   // Threads

   public synchronized void sendGetFrameLocals(final String frameNum,
                                               final String threadId,
                                               final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:frame-locals-for-emacs " + frameNum + ")";

      emacsRexWithThread(msg, threadId);
   }

   public synchronized void sendGetFrameSourceLocation(final String frameNum,
                                                       final String threadId,
                                                       final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:frame-source-location-for-emacs " + frameNum +
               ")";

      emacsRexWithThread(msg, threadId);
   }

   public synchronized void sendGetInstalledPackages(final SwankRunnable callBack) {
      if (this.managePackages) {
         registerCallback(callBack);
         final String msg = "(swank:eval-and-grab-output \"" +
                  LispUtil.formatCode("(get-installed-packages)") + "\")";
         emacsRex(msg, "com.gigamonkeys.asdf-extensions");
      }
      else {
         callBack.result = null;
         callBack.run();
      }
   }

   public synchronized void sendInspectFrameLocal(final String threadNum,
                                                  final String frameNum,
                                                  final String varNum,
                                                  final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:inspect-frame-var " + frameNum + " " + varNum +
               ")";

      emacsRexWithThread(msg, threadNum);
   }

   public synchronized void sendInspectInspectedPart(final String partNum,
                                                     final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:inspect-nth-part " + partNum + ")";

      emacsRex(msg);
   }

   // Compiling

   public synchronized void sendInspectorNext(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:inspector-next)";
      emacsRex(msg);
   }

   public synchronized void sendInspectorPop(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:inspector-pop)";
      emacsRex(msg);
   }

   public synchronized void sendInspectReplResult(final String num,
                                                  final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:init-inspector \"(swank:get-repl-result #10r" +
               num + ")\" )";

      emacsRex(msg);
   }

   public synchronized void sendInterrupt(final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(:emacs-interrupt :repl-thread)\n";

      sendRaw(msg);
   }

   public synchronized void sendKillThread(final String threadNum,
                                           final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:kill-nth-thread " + threadNum + ")";

      emacsRex(msg);
   }

   public synchronized void sendListThreads(final SwankRunnable callBack) {
      registerCallback(callBack);
      emacsRex("(swank:list-threads)");
   }

   public synchronized void sendLoadASDFtoRemove(String fileFullPath,
                                                 final SwankRunnable callBack) {
      fileFullPath = fileFullPath.replace('\\', '/');
      fileFullPath = this.implementation.translateLocalFilePath(fileFullPath);
      final String[] fpathparts = fileFullPath.split("/");
      if ((fpathparts.length > 0) &&
               fpathparts[fpathparts.length - 1].matches(".+\\.asd")) {
         registerCallback(callBack);
         final String asdName = fpathparts[fpathparts.length - 1].replace(".asd",
                                                                          "");
         // Note from Tim:
         // I changed this back, because the newer implementation assumed
         // that (load ...) will get done in two seconds, which is a false
         // assumption, particularly on most of my pet projects.
         // If you want to alter this, you'll need to make sure the
         // load-op command is not issued until load is done.
         // Might need some callback-fu.
         final String msg = "(cl:progn (cl:load \"" + fileFullPath +
                  "\") (asdf:oos 'asdf:load-op \"" + asdName + "\"))";

         emacsRex(msg);
      }
      // old version of code:
      /*
       * fileFullPath = fileFullPath.replace('\\', '/'); fileFullPath =
       * implementation.translateLocalFilePath(fileFullPath); String[]
       * fpathparts = fileFullPath.split("/"); if( fpathparts.length > 0 &&
       * fpathparts[fpathparts.length-1].matches(".+\\.asd") ){ String tmp =
       * sendEvalAndGrab("(load \"" + fileFullPath + "\")",2000); String asdName
       * = fpathparts[fpathparts.length-1].replace(".asd", "");
       * registerCallback(new CompileRunnable(callBack)); String msg =
       * "(swank:operate-on-system-for-emacs \"" + asdName + "\" \"LOAD-OP\")";
       * emacsRex(msg); }
       */

   }

   public synchronized void sendLoadPackage(final String pkg) {
      sendEvalAndGrab("(asdf:operate 'asdf:load-op :" + pkg + ")", 3000);
   }

   public synchronized void sendMacroExpand(final String code,
                                            final SwankRunnable callBack,
                                            final boolean all,
                                            final String pckg) {
      registerCallback(callBack);
      String msg;
      if (all) {
         msg = "(swank:swank-macroexpand-all \"" + LispUtil.formatCode(code) +
                  "\")";
      }
      else {
         msg = "(swank:swank-macroexpand-1 \"" + LispUtil.formatCode(code) +
                  "\")";
      }

      emacsRex(msg, pckg);
   }

   public synchronized void sendProfileReset(final SwankRunnable callBack) {
      registerCallback(callBack);
      emacsRex("(swank:profile-reset)");
   }

   public synchronized void sendQuitDebug(final SwankRunnable callBack,
                                          final String threadId) {
      registerCallback(callBack);
      final String msg = "(swank:throw-to-toplevel)";

      emacsRexWithThread(msg, threadId);
   }

   // X-ref

   public synchronized boolean sendRaw(final String message) {
      // message = message + "\n";
      System.out.println("-->" + message);
      try {
         if (this.out != null) {
            // Messages are prepending by their length, given as a 6-char string
            // which is a hexadecimal number. It tells you how many bytes to
            // read
            // and hexadecimal expresses a larger range than decimal (and who's
            // going to read 16M?)
            final String hexLen = Integer.toHexString(message.length());

            switch (hexLen.length()) {
               case 1:
                  this.out.write('0');
               case 2:
                  this.out.write('0');
               case 3:
                  this.out.write('0');
               case 4:
                  this.out.write('0');
               case 5:
                  this.out.write('0');
            }

            this.out.writeBytes(hexLen);
            this.out.write(message.getBytes("UTF-8"));
            this.out.flush();
         }
         else {
            return false;
         }
      }
      catch (final IOException e) {
         signalListeners(this.disconnectListeners, new LispNode());
         e.printStackTrace();
         return false;
      }
      return true;
   }

   public synchronized void sendReadString(final String input,
                                           final SwankRunnable callBack,
                                           final String num1,
                                           final String num2) {
      registerCallback(callBack);
      final String msg = "(:emacs-return-string " + num1 + " " + num2 + " \"" +
               LispUtil.formatCode(input) + "\")";

      sendRaw(msg);
   }

   // Profiling

   public synchronized void sendReportProfile(final SwankRunnable callBack) {
      registerCallback(callBack);
      emacsRex("(swank:profile-report)");
      // FIXME: for some reason the report is shown only after something
      // else is evaluated
      emacsRex("(swank:listener-eval \"nil\")");
   }

   public synchronized void sendRunTests(final String pkg,
                                         final SwankRunnable callBack) {
      registerCallback(callBack);
      this.lastTestPackage = pkg;
      String msg = "(lisp-unit:run-all-tests " + pkg + ")";
      // msg = "(let ((*standard-output* str))"+msg+")";
      // msg = "(with-output-to-string (str) "+msg+"str)";
      msg = "(swank:eval-and-grab-output \"" + msg + "\")";

      emacsRex(msg, "COMMON-LISP-USER");
   }

   public synchronized void sendStepDebug(final SwankRunnable callBack,
                                          final String threadId) {
      registerCallback(callBack);
      final String msg = "(swank:sldb-step 0)";

      emacsRexWithThread(msg, threadId);
   }

   // Message and response/reply management:

   public synchronized void sendToggleProfileFunction(final String functionName,
                                                      final String pkg,
                                                      final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:toggle-profile-fdefinition \"" +
               LispUtil.formatCode(functionName) + "\")";
      emacsRex(msg, pkg);
   }

   public synchronized void sendUndefine(final String symbol,
                                         final String pkg,
                                         final SwankRunnable callBack) {
      registerCallback(callBack);
      final String msg = "(swank:undefine-function \"" +
               LispUtil.formatCode(symbol) + "\")";

      emacsRex(msg, pkg);
   }

   public synchronized void sendUndefineTest(final String symbol,
                                             final String pkg,
                                             final SwankRunnable callBack) {
      registerCallback(callBack);
      this.lastTestPackage = pkg;
      String msg = "(lisp-unit:remove-tests '(" + pkg + "::" +
               LispUtil.formatCode(symbol) + ") '" + pkg + ")";
      // msg = "(let ((*standard-output* str))"+msg+")";
      // msg = "(with-output-to-string (str) "+msg+"str)";
      msg = "(swank:eval-and-grab-output \"" + msg + "\")";

      emacsRex(msg, "COMMON-LISP-USER");
   }

   public void setLispVersion(final String version) {
      this.lispVersion = version;
      LispPlugin.getDefault().updateReplStatusLine("CL:" + this.lispVersion +
               "| Cusp: " + LispPlugin.getVersion() + "| Current package: " +
               getPackage());
   }

   public synchronized void setPackage(final String p) {
      ++this.messageNum;
      final String newPackage = LispUtil.formatCode(p);
      emacsRex("(swank:set-package \"" + newPackage + "\")", this.currPackage);

      this.currPackage = newPackage;
   }

   public String translateRemoteFilePath(final String path) {
      if (this.implementation != null) {
         return this.implementation.translateRemoteFilePath(path);
      }
      else {
         return path;
      }
   }

   protected synchronized void sendPong(final String n1, final String n2) {
      final String msg = "(:emacs-pong " + n1 + " " + n2 + ")";
      sendRaw(msg);
   }

   private boolean connectStreams(final String slimePath) {
      if (this.stdOut != null) { // never cross the streams
         this.stdOut.running = false;
      }
      if (this.stdErr != null) {
         this.stdErr.running = false;
      }

      // it's not happy unless we hook up to clear out the output
      final SwankStartFilter ssfilter = new SwankStartFilter();

      int retries = this.connect_retries;

      this.stdOut = new DisplayListenerThread(this.lispEngine.getInputStream(),
                                              true);
      this.stdErr = new DisplayListenerThread(this.lispEngine.getErrorStream(),
                                              true);

      this.stdOut.addFilter(ssfilter);
      this.stdErr.addFilter(ssfilter);

      this.stdOut.start();
      this.stdErr.start();

      try {
         this.commandInterface = new DataOutputStream(this.lispEngine.getOutputStream());
         // commandInterface.writeBytes("(progn (swank:create-swank-server " +
         // port + " nil) (quit))\n");
         // commandInterface.writeBytes("(load \"" + slimePath.replace("\\",
         // "\\\\") + "\")\n");
         final String slimeLoadCmd = this.implementation.getLoadSwankCommand();
         if (slimeLoadCmd != null) {
            this.commandInterface.writeBytes(slimeLoadCmd);
         }
         this.commandInterface.writeBytes("(swank-loader::init :load-contribs t :setup t)");
         this.commandInterface.flush();
         this.commandInterface.writeBytes("(progn (swank:create-server :coding-system \"utf-8\" :port " +
                  SwankInterface.port + "))\n");
         this.commandInterface.flush();

         while ( !ssfilter.getStartStringFlag() && (retries > 0)) {
            synchronized (ssfilter) {
               try {
                  // Wait until we detect the "Swank started" message in the
                  // display stream
                  ssfilter.wait(10000);
               }
               catch (final Exception e) {
                  // ignore all exceptions (this might be dangerous)
               }
               finally {
                  if ( !ssfilter.getStartStringFlag()) {
                     System.err.println("still waiting for 'Swank started' string... retries = " +
                              retries);
                     --retries;
                  }
               }
            }
         }
      }
      catch (final IOException e2) {
         e2.printStackTrace();
         return false;
      }
      finally {
         this.stdOut.removeFilter(ssfilter);
         this.stdErr.removeFilter(ssfilter);
      }

      return (retries > 0);
   }

   // finds definitions in package pkg:
   private synchronized boolean haveDefinitionsPkg(final String symbol,
                                                   final String pkg,
                                                   final long timeout) {
      final SyncCallback callBack = new SyncCallback();
      ++this.messageNum;
      this.syncJobs.put(new Integer(this.messageNum).toString(), callBack);

      final String code = LispUtil.formatCode(symbol);
      String pkgstring = pkg;
      if ( !pkg.equals("") && pkg.startsWith(":")) {
         pkgstring = pkg.substring(1);
      }
      if ( !pkgstring.equals("")) {
         pkgstring += "::";
      }
      String msg = "(handler-case (swank:find-definitions-for-emacs \"" +
               pkgstring + code + "\") (simple-type-error () nil))";
      if (this.implementation.lispType().equalsIgnoreCase("SBCL")) { // quiet
         // compilation
         // warnings
         msg = "(locally (declare (sb-ext:muffle-conditions sb-ext:compiler-note)) " +
                  msg + ")";
      }
      final String res = sendEvalAndGrab(msg, 2000);

      return ( !(res.equalsIgnoreCase("nil") || (res.contains(":ERROR") && !res.contains(":LOCATION"))));
   }

   private void initIndents() {
      // for forms that get indented like flet
      this.fletIndents = new Hashtable<String, String>();
      this.fletIndents.put("flet", "  ");
      this.fletIndents.put("labels", "  ");
      this.fletIndents.put("macrolet", "  ");

      // for forms that get indented like handler-case
      this.handlerCaseIndents = new Hashtable<String, String>();
      this.handlerCaseIndents.put("handler-case", "  ");

      // forms that always get indented a certain number of spaces
      // Why are flet, labels, etc here as well as in fletIndents?
      // specialIndents controls how you get indented as a result of your parent
      // form
      // The indenting we do for fletIndents happens when those forms are the
      // great-grandparents
      this.specialIndents = new Hashtable<String, String>();
      this.specialIndents.put("", " ");
      this.specialIndents.put("progn", "  ");
      this.specialIndents.put("if", "    ");
      this.specialIndents.put("cond", "  ");
      this.specialIndents.put("when", "  ");
      this.specialIndents.put("unless", "  ");
      this.specialIndents.put("let", "  ");
      this.specialIndents.put("let*", "  ");
      this.specialIndents.put("dolist", "  ");
      this.specialIndents.put("flet", "  ");
      this.specialIndents.put("labels", "  ");
      this.specialIndents.put("macrolet", "  ");
      this.specialIndents.put("dotimes", "  ");
      this.specialIndents.put("lambda", "  ");
      this.specialIndents.put("defun", "  ");
      this.specialIndents.put("defvar", "  ");
      this.specialIndents.put("defparameter", "  ");
      this.specialIndents.put("eval-when", "  ");
      this.specialIndents.put("multiple-value-bind", "  ");
      this.specialIndents.put("destructuring-bind", "  ");
      this.specialIndents.put("unwind-protect", "  ");
      this.specialIndents.put("block", "  ");
      this.specialIndents.put("case", "  ");

      // All additional custom indents will go here
      this.indents = new Hashtable<String, Integer>();
      this.indents.put("do", 2);
      this.indents.put("with-open-stream", 1);
      this.indents.put("with-open-file", 1);
      this.indents.put("with-accessors", 1);
      this.indents.put("with-alien", 1);
      this.indents.put("with-compilation-unit", 1);
      this.indents.put("with-compilation-restarts", 1);
      this.indents.put("with-condition-restarts", 1);
      this.indents.put("with", 1);
      this.indents.put("with-hash-table-iterator", 1);
      this.indents.put("with-input-from-string", 1);
      this.indents.put("with-locked-hash-table", 1);
      this.indents.put("with-output-to-string", 1);
      this.indents.put("with-package-iterator", 1);
      this.indents.put("with-simple-restart", 1);
      this.indents.put("with-slots", 2);
      this.indents.put("with-timeout", 1);
      this.indents.put("with-unlocked-packages", 1);

      addIndentationListener(new SwankRunnable() {
         @Override
         public void run() {
            final LispNode updates = this.result.get(1);
            for (final LispNode update : updates.params) {
               final String symbol = update.get(0).value;
               // The LispParser doesn't understand dotted lists,
               // and just sees the dot as another element
               final int paramNum = update.get(2).asInt();
               SwankInterface.this.indents.put(symbol, paramNum);
            }

         }
      });
   }

   private void registerCallback(final SwankRunnable callBack) {
      ++this.messageNum;
      if (callBack != null) {
         callBack.messageNum = this.messageNum;
         this.jobs.put(new Integer(this.messageNum).toString(), callBack);
      }
   }

   private void signalListeners(final List<SwankRunnable> listeners,
                                final LispNode result) {
      synchronized (listeners) {
         for (final SwankRunnable l : listeners) {
            final SwankRunnable runnable = l.clone();
            runnable.result = result;
            // runnable.resultString = result.value;
            Display.getDefault().asyncExec(runnable);
         }
      }
   }

   private void signalResponse(final LispNode reply) {
      final String jobNum = reply.get(reply.params.size() - 1).value;
      final Object r = this.jobs.get(jobNum);
      if (r != null) {
         final SwankRunnable runnable = (SwankRunnable) r;
         runnable.result = reply;
         Display.getDefault().asyncExec(runnable);
         this.jobs.remove(jobNum);
      }
      else {
         final SyncCallback sync = this.syncJobs.get(jobNum);
         if (sync != null) {
            sync.result = reply;
            this.syncJobs.remove(jobNum);
            synchronized (sync) {
               sync.notifyAll();
            }
         } // if
      } // else
   }

} // class

